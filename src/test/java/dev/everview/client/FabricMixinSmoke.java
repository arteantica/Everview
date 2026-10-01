package dev.everview.client;

import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Actual client-side Mixin transformation of the packaged mod, without starting the game. */
public final class FabricMixinSmoke {
    private record MixinTarget(List<String> classes, List<String> handlers) {}

    public static void main(String[] args) throws Exception {
        Path mod = Path.of(args[0]).toAbsolutePath();
        Map<String, MixinTarget> mixins = new LinkedHashMap<>();
        try (var jar = new JarFile(mod.toFile());
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("everview.mixins.json")),
                     StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            String prefix = config.get("package").getAsString() + ".";
            for (var entry : config.getAsJsonArray("client")) {
                String name = prefix + entry.getAsString();
                try (var bytes = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
                    mixins.put(name, inspect(new ClassReader(bytes)));
                }
            }
        }
        if (mixins.isEmpty()) throw new AssertionError("No packaged client mixins discovered");
        var knot = new Knot(EnvType.CLIENT);
        ClassLoader loader = knot.init(new String[]{"--gameDir", ".", "--version", "26.3"});
        int checked = 0;
        for (var entry : mixins.entrySet()) {
            var mixin = entry.getValue();
            if (mixin.classes.isEmpty() || mixin.handlers.isEmpty())
                throw new AssertionError("Missing targets/handlers for " + entry.getKey());
            for (String target : mixin.classes) {
                // Loading through Knot applies Fabric API + Everview's required mixins.
                // Declared methods also force JVM signature resolution/verification.
                Class<?> transformed = Class.forName(target, false, loader);
                if (transformed.getClassLoader() != loader)
                    throw new AssertionError("Target bypassed Knot: " + target);
                Set<String> methods = new HashSet<>();
                for (var method : transformed.getDeclaredMethods()) methods.add(method.getName());
                for (String handler : mixin.handlers) {
                    if (methods.stream().noneMatch(name -> name.endsWith("$" + handler)))
                        throw new AssertionError("Handler was not applied: " + target + " / " + handler);
                    checked++;
                }
                System.out.println("TRANSFORMED " + target + " " + mixin.handlers);
            }
        }
        if (!Class.forName("dev.everview.client.VanillaTerrainReadiness", false, loader)
                .isAssignableFrom(Class.forName("net.minecraft.client.renderer.LevelRenderer", false, loader)))
            throw new AssertionError("Drawable-readiness interface was not applied");
        verifyTerrainPipeline(loader, mod);
        System.out.println("PASS: " + mixins.size() + " packaged client mixins / " + checked
                + " required handlers transformed and JVM-verified by Fabric Loader");
    }

    private static void verifyTerrainPipeline(ClassLoader loader, Path mod) throws Exception {
        // Unlike class-only Mixin checks, this executes the production initializer
        // that the first terrain draw uses, including Minecraft's actual builder.
        Class<?> pipelineClass = Class.forName("dev.everview.client.EverviewGpuPipeline", true, loader);
        if (pipelineClass.getClassLoader() != loader) throw new AssertionError("Pipeline bypassed Knot");
        Object pipeline = pipelineClass.getField("TERRAIN").get(null);
        Class<?> type = pipeline.getClass();
        if (!type.getMethod("getLocation").invoke(pipeline).toString()
                .equals("everview:pipeline/persistent_lod_terrain"))
            throw new AssertionError("Unexpected terrain pipeline location");
        Map<?, ?> shaders = (Map<?, ?>) type.getMethod("getShaders").invoke(pipeline);
        if (shaders.size() != 2) throw new AssertionError("Both shader stages must exist");
        Set<String> stages = new HashSet<>();
        try (var jar = new JarFile(mod.toFile())) {
            for (var entry : shaders.entrySet()) {
                String stage = entry.getKey().toString();
                stages.add(stage);
                String id = entry.getValue().toString();
                if (!id.equals("everview:core/terrain"))
                    throw new AssertionError("Shader namespace/path is wrong: " + id);
                String extension = switch (stage) {
                    case "VERTEX" -> ".vsh";
                    case "FRAGMENT" -> ".fsh";
                    default -> throw new AssertionError("Unexpected shader stage " + stage);
                };
                String asset = "assets/everview/shaders/core/terrain" + extension;
                if (jar.getJarEntry(asset) == null)
                    throw new AssertionError("Pipeline shader is absent from the packaged JAR: " + asset);
            }
        }
        if (!stages.equals(Set.of("VERTEX", "FRAGMENT"))) throw new AssertionError("Missing shader stage");
        Object depth = type.getMethod("getDepthStencilState").invoke(pipeline);
        if (!Boolean.TRUE.equals(depth.getClass().getMethod("writeDepth").invoke(depth)))
            throw new AssertionError("Terrain must write depth");
        Set<String> uniforms = new HashSet<>();
        for (Object layout : (List<?>) type.getMethod("getBindGroupLayouts").invoke(pipeline)) {
            for (Object uniform : (List<?>) layout.getClass().getMethod("uniforms").invoke(layout)) {
                String name = uniform.getClass().getMethod("name").invoke(uniform).toString();
                if (!uniforms.add(name)) throw new AssertionError("Duplicate pipeline uniform " + name);
            }
        }
        if (!uniforms.equals(Set.of("Globals", "Projection", "DynamicTransforms", "EverviewOwnership")))
            throw new AssertionError("Incomplete terrain/ownership bindings: " + uniforms);
        System.out.println("PASS: production terrain pipeline initialized; both namespaced shader assets,"
                + " depth writes and terrain/ownership uniform bindings verified");
    }

    private static MixinTarget inspect(ClassReader reader) {
        List<String> targets = new ArrayList<>(), handlers = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        if (!name.equals("value") && !name.equals("targets")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String key, Object value) {
                                targets.add(value instanceof Type type ? type.getClassName() : value.toString());
                            }
                        };
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                        String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (Set.of("Lorg/spongepowered/asm/mixin/injection/Inject;",
                                "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
                                "Lorg/spongepowered/asm/mixin/injection/Redirect;").contains(annotation))
                            handlers.add(name);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new MixinTarget(targets, handlers);
    }
}
