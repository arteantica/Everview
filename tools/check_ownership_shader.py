#!/usr/bin/env python3
"""Compile/link the production shaders and exercise ownership masking with software EGL.
No Minecraft FPS claim: this is an actual GL framebuffer correctness test."""
import ctypes as C
import ctypes.util
import pathlib
import re
import sys
import zipfile

root=pathlib.Path(__file__).resolve().parents[1]
client=pathlib.Path(sys.argv[1])
egl=C.CDLL(ctypes.util.find_library('EGL'))
gl=C.CDLL(ctypes.util.find_library('GL'))
def bind(lib,name,restype,*args):
    f=getattr(lib,name);f.restype=restype;f.argtypes=list(args);return f
I=C.c_int;U=C.c_uint;P=C.c_void_p
getproc=bind(egl,'eglGetProcAddress',P,C.c_char_p)
getdisplay=C.CFUNCTYPE(P,U,P,C.POINTER(I))(getproc(b'eglGetPlatformDisplayEXT'))
display=getdisplay(0x31DD,None,None)
assert bind(egl,'eglInitialize',U,P,C.POINTER(I),C.POINTER(I))(display,None,None)
assert bind(egl,'eglBindAPI',U,U)(0x30A2)
attrs=(I*13)(0x3024,8,0x3023,8,0x3022,8,0x3033,1,0x3040,8,0x3021,8,0x3038)
config=P();count=I()
assert bind(egl,'eglChooseConfig',U,P,C.POINTER(I),C.POINTER(P),I,C.POINTER(I))(display,attrs,C.byref(config),1,C.byref(count)) and count.value
ctxattrs=(I*7)(0x3098,3,0x30FB,3,0x30FD,1,0x3038)
context=bind(egl,'eglCreateContext',P,P,P,P,C.POINTER(I))(display,config,None,ctxattrs)
surfattrs=(I*5)(0x3057,4,0x3056,4,0x3038)
surface=bind(egl,'eglCreatePbufferSurface',P,P,P,C.POINTER(I))(display,config,surfattrs)
assert bind(egl,'eglMakeCurrent',U,P,P,P,P)(display,surface,surface,context)
archive=zipfile.ZipFile(client)
def expand(s):
    return re.sub(r'#include <minecraft:([^>]+)>',lambda m:archive.read('assets/minecraft/shaders/include/'+m[1]).decode(),s)
create=bind(gl,'glCreateShader',U,U);source=bind(gl,'glShaderSource',None,U,I,C.POINTER(C.c_char_p),C.POINTER(I))
compile_shader=bind(gl,'glCompileShader',None,U);query=bind(gl,'glGetShaderiv',None,U,U,C.POINTER(I))
shaders=[]
for name,kind in [('terrain.vsh',0x8B31),('terrain.fsh',0x8B30)]:
    shader=create(kind);s=C.c_char_p(expand((root/'src/main/resources/assets/everview/shaders/core'/name).read_text()).encode())
    source(shader,1,C.byref(s),None);compile_shader(shader);ok=I();query(shader,0x8B81,C.byref(ok))
    log=C.create_string_buffer(8192);bind(gl,'glGetShaderInfoLog',None,U,I,C.POINTER(I),P)(shader,8192,None,log)
    assert ok.value,(name,log.value.decode());shaders.append(shader)
program=bind(gl,'glCreateProgram',U)()
for s in shaders:bind(gl,'glAttachShader',None,U,U)(program,s)
bind(gl,'glLinkProgram',None,U)(program);ok=I();bind(gl,'glGetProgramiv',None,U,U,C.POINTER(I))(program,0x8B82,C.byref(ok))
log=C.create_string_buffer(8192);bind(gl,'glGetProgramInfoLog',None,U,I,C.POINTER(I),P)(program,8192,None,log);assert ok.value,log.value.decode()
print('Production terrain shaders compiled and linked using EGL/OpenGL.')
# Verify real fragment discard from the production fragment shader using a tiny test vertex stage.
vs=create(0x8B31)
s=C.c_char_p(b'''#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) out vec4 vertexColor;
layout(location=1) out vec2 ownershipPosition;
uniform vec2 TestPosition;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);vertexColor=vec4(0,1,0,1);ownershipPosition=TestPosition;}''')
source(vs,1,C.byref(s),None);compile_shader(vs);query(vs,0x8B81,C.byref(ok));assert ok.value
p=bind(gl,'glCreateProgram',U)()
for s in [vs,shaders[1]]:bind(gl,'glAttachShader',None,U,U)(p,s)
bind(gl,'glLinkProgram',None,U)(p);bind(gl,'glGetProgramiv',None,U,U,C.POINTER(I))(p,0x8B82,C.byref(ok));assert ok.value
bind(gl,'glUseProgram',None,U)(p)
ids=(U*2)();bind(gl,'glGenBuffers',None,I,C.POINTER(U))(2,ids)
getblock=bind(gl,'glGetUniformBlockIndex',U,U,C.c_char_p)
for index,name in enumerate([b'DynamicTransforms',b'EverviewOwnership']):
    b=getblock(p,name);bind(gl,'glUniformBlockBinding',None,U,U,U)(p,b,index)
    bind(gl,'glBindBufferBase',None,U,U,U)(0x8A11,index,ids[index])
# std140 matrices 128 bytes, then ColorModulator.
uniforms=(C.c_float*40)();uniforms[32:36]=[1,1,1,1]
bind(gl,'glBindBuffer',None,U,U)(0x8A11,ids[0]);bind(gl,'glBufferData',None,U,C.c_ssize_t,P,U)(0x8A11,C.sizeof(uniforms),uniforms,0x88E8)
mask=(U*2048)();mask[0]=1  # chunk (0,0) owned by vanilla
bind(gl,'glBindBuffer',None,U,U)(0x8A11,ids[1]);bufferdata=bind(gl,'glBufferData',None,U,C.c_ssize_t,P,U);bufferdata(0x8A11,C.sizeof(mask),mask,0x88E8)
vao=U();bind(gl,'glGenVertexArrays',None,I,C.POINTER(U))(1,C.byref(vao));bind(gl,'glBindVertexArray',None,U)(vao)
loc=bind(gl,'glGetUniformLocation',I,U,C.c_char_p)(p,b'TestPosition')
bind(gl,'glViewport',None,I,I,I,I)(0,0,4,4)
for cycle in range(4):
    for x,z,blocked in [(8,8,True),(24,8,False),(16,8,True),(8,16,True),(-1,-1,False),(4097,4097,False)]:
        bind(gl,'glUniform2f',None,I,C.c_float,C.c_float)(loc,x,z)
        bind(gl,'glClearColor',None,C.c_float,C.c_float,C.c_float,C.c_float)(1,0,0,1);bind(gl,'glClear',None,U)(0x4000)
        bind(gl,'glDrawArrays',None,U,I,I)(4,0,3)
        pixel=(C.c_ubyte*4)();bind(gl,'glReadPixels',None,I,I,I,I,U,U,P)(1,1,1,1,0x1908,0x1401,pixel)
        expected=blocked if cycle%2==0 else False
        assert (pixel[0]>200)==expected,(cycle,x,z,list(pixel),expected)
    mask[0]=0 if cycle%2==0 else 1;bufferdata(0x8A11,C.sizeof(mask),mask,0x88E8)
print('24 framebuffer checks passed: vanilla loading/unloading, reused draw, water/terrain shared path, boundary seams and out-of-mask coverage.')
