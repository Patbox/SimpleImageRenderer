#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0)  in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main(){
    vec4 color = texture(InSampler, texCoord);
    if (color.a < 0.01) {
        fragColor = vec4(color.r, color.g, color.b, 0);
    } else {
        fragColor = vec4(color.r, color.g, color.b, 1);
    }
}
