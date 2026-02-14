#version 330

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;

out vec4 fragColor;

void main(){
    vec4 color = texture(InSampler, texCoord);
    if (color.a < 0.01) {
        fragColor = vec4(color.r, color.g, color.b, 0);
    } else {
        fragColor = vec4(color.r, color.g, color.b, 1);
    }
}
