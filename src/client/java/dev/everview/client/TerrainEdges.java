package dev.everview.client;

import java.util.*;

/** Actual top-surface edge profiles after ownership splitting; not guessed skirt depths. */
public record TerrainEdges(Map<Edge, List<Segment>> profiles, long estimatedBytes) {
    public record Edge(int cellX, int cellZ, int side) {
        // 0 west, 1 east, 2 north, 3 south
        public Edge opposite() {
            return new Edge(cellX + (side == 0 ? -1 : side == 1 ? 1 : 0),
                    cellZ + (side == 2 ? -1 : side == 3 ? 1 : 0), side ^ 1);
        }
    }
    public record Segment(int start, int end, int y0, int y1, int color, byte material) {
        public float height(float t) { return y0 + (y1 - y0) * ((t - start) / (end - start)); }
    }

    static TerrainEdges from(EverviewGpuTileCache.PreparedGeometry mesh, int originX, int originZ) {
        Map<Edge, List<Segment>> profiles = new HashMap<>();
        int[] v = mesh.vertices();
        for (int q = 0; q < v.length; q += 12) {
            int x0 = v[q] + originX, z0 = v[q + 2] + originZ;
            int x1 = v[q + 6] + originX, z1 = v[q + 8] + originZ;
            // Generated top quads have 00,01,11,10 winding. Vertical faces add no profile.
            if (x1 <= x0 || z1 <= z0) continue;
            int color = mesh.colors()[q / 3]; byte material = mesh.materials()[q / 3];
            if (Math.floorMod(x0, 128) == 0) add(profiles, new Edge(Math.floorDiv(x0,128),Math.floorDiv(z0,128),0),
                    new Segment(z0,z1,v[q+1],v[q+4],color,material));
            if (Math.floorMod(x1, 128) == 0) add(profiles, new Edge(Math.floorDiv(x1-1,128),Math.floorDiv(z0,128),1),
                    new Segment(z0,z1,v[q+10],v[q+7],color,material));
            if (Math.floorMod(z0, 128) == 0) add(profiles, new Edge(Math.floorDiv(x0,128),Math.floorDiv(z0,128),2),
                    new Segment(x0,x1,v[q+1],v[q+10],color,material));
            if (Math.floorMod(z1, 128) == 0) add(profiles, new Edge(Math.floorDiv(x0,128),Math.floorDiv(z1-1,128),3),
                    new Segment(x0,x1,v[q+4],v[q+7],color,material));
        }
        profiles.replaceAll((key, segments) -> {
            segments.sort(Comparator.comparingInt(Segment::start));
            // Ownership splitting can divide one terrace edge into many 16b pieces.
            // Coalesce identical flat profiles; final vanilla masking still runs per fragment.
            var joined=new ArrayList<Segment>(segments.size());
            for(var next:segments){
                if(!joined.isEmpty()){
                    var previous=joined.getLast();
                    if(previous.end()==next.start() && previous.y0()==previous.y1()
                            && next.y0()==next.y1() && previous.y1()==next.y0()
                            && previous.color()==next.color() && previous.material()==next.material()){
                        joined.set(joined.size()-1,new Segment(previous.start(),next.end(),previous.y0(),next.y1(),next.color(),next.material()));continue;
                    }
                }
                joined.add(next);
            }
            return List.copyOf(joined);
        });
        long bytes = profiles.size() * 80L;
        for (var segments : profiles.values()) bytes += segments.size() * 40L;
        return new TerrainEdges(Map.copyOf(profiles), bytes);
    }

    private static void add(Map<Edge,List<Segment>> map, Edge key, Segment segment) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segment);
    }

}
