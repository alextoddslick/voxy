#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif


#ifdef USE_NV_JANK
#extension GL_NV_gpu_shader5 : enable
#endif

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

//VS_FLAT is #define'd by the Java Shader.Builder call site (MDICSectionRenderer) to "flat" on
// every driver except Mesa, and to nothing on Mesa. Mesa/Zink (as used by KosmicKrisp on macOS)
// has a compiler bug where an explicit interpolation qualifier (flat/smooth/noperspective/
// centroid) on a vertex shader "out" triggers a spurious "invalid xfb_buffer" validation error
// whenever the driver reports GL_MAX_TRANSFORM_FEEDBACK_BUFFERS=0 (as KosmicKrisp does, since it
// has no transform-feedback support at all) - it implicitly assigns/validates a default
// xfb_buffer=0 for interpolation-qualified outputs even though no XFB capture is ever requested.
// GLSL allows the interpolation qualifier to be declared on just one side of a matched in/out
// pair, and quads.frag's matching "in" always declares "flat" regardless of VS_FLAT here, so
// dropping it on Mesa only is lossless there while non-Mesa drivers keep the qualifier on both
// sides as before.
layout(location = 0) out VS_FLAT uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

#ifdef USE_NV_JANK
#ifdef GL_NV_gpu_shader5
out gl_PerVertex {
    f16vec4 gl_Position;
};
#endif
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out VS_FLAT uint quadDebug;//see note above re: VS_FLAT and Mesa/Zink
#endif

vec2 taaShift();

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    setupQuad(quad, quadData[uint(gl_VertexID)>>2], pos, (gl_VertexID&3) == 1);

    uint cornerId = gl_VertexID&3;

    gl_Position =
    #ifdef USE_NV_JANK
    #ifdef GL_NV_gpu_shader5
    f16vec4
    #endif
    #endif
    (getQuadCornerPos(quad, cornerId));


    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;


    #ifdef DEBUG_RENDER
    //quadDebug = uint(extractDetail(pos));
    quadDebug = uint(gl_VertexID)>>2;
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif