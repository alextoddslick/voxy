#version 450
#extension GL_ARB_shader_draw_parameters : require

layout(binding = 0, std140) uniform SceneUniform {
    mat4 VP;
    ivec3 camSecPos;
    uint screenW;
    vec3 camSubSecPos;
    uint screenH;
};

#define NODE_DATA_INDEX 1
#import <voxy:lod/hierarchical/node.glsl>

layout(binding = 2, std430) restrict buffer NodeList {
    uint count;
    uint nodeQueue[];
};

//VS_FLAT is #define'd by the Java Shader.Builder call site (DebugRenderer) to "flat" on every
// driver except Mesa, and to nothing on Mesa - see quads3.vert for why (Mesa/Zink KosmicKrisp
// compiler bug: spurious xfb_buffer error when GL_MAX_TRANSFORM_FEEDBACK_BUFFERS=0 and a vertex
// "out" has an interpolation qualifier).
layout(location = 1) out VS_FLAT vec4 colour;

void main() {
    UnpackedNode node;
    unpackNode(node, nodeQueue[gl_InstanceID]);

    vec4 base = VP*vec4(vec3(((node.pos<<node.lodLevel)-camSecPos)<<5)-camSubSecPos, 1);

    vec4 pos = base + (VP*vec4(ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)<<(5+node.lodLevel), 1));

    gl_Position = pos;

    //node.nodeId
    uint hash = node.nodeId*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    //colour = vec4(vec3(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15), 1);
    colour = vec4(vec3(float(hash&31u)/31, float(node.lodLevel)/4, float(node.lodLevel)/4), 1);
}