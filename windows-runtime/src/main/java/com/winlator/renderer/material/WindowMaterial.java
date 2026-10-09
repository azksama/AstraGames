package com.winlator.renderer.material;

public class WindowMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform xform = new Uniform("xform");
        public final Uniform viewSize = new Uniform("viewSize");
        public final Uniform texture = new Uniform("texture");
        public final Uniform noAlpha = new Uniform("noAlpha");
        public final Uniform flipY = new Uniform("flipY");
        public final Uniform smooth = new Uniform("smoothScaling");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "uniform float xform[6];",
            "uniform vec2 viewSize;",
            "uniform bool flipY;",

            "in vec2 position;",
            "out vec2 vUV;",

            "void main() {",
                "vUV = vec2(position.x, flipY ? (1.0 - position.y) : position.y);",
                "vec2 transformedPos = applyXForm(position, xform);",
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "uniform sampler2D windowTexture;",
            "uniform float noAlpha;",
            "uniform bool smoothScaling;",
            "in vec2 vUV;",

            "layout(location = 0) out vec4 outFragColor;",

            // Four bilinear fetches implement a cubic B-spline when magnifying.
            "vec4 cubic(float f) { float f2=f*f; float f3=f2*f; float g=1.0-f; return vec4(g*g*g, 3.0*f3-6.0*f2+4.0, -3.0*f3+3.0*f2+3.0*f+1.0, f3)/6.0; }",
            "vec4 sampleSmooth(vec2 uv) {",
                "vec2 size = vec2(textureSize(windowTexture, 0));",
                "vec2 p = uv * size - 0.5; vec2 f = fract(p); p -= f;",
                "vec4 wx = cubic(f.x); vec4 wy = cubic(f.y);",
                "vec2 gx = vec2(wx.x+wx.y, wx.z+wx.w); vec2 gy = vec2(wy.x+wy.y, wy.z+wy.w);",
                "vec2 x = (p.x + vec2(-0.5 + wx.y/gx.x, 1.5 + wx.w/gx.y))/size.x;",
                "vec2 y = (p.y + vec2(-0.5 + wy.y/gy.x, 1.5 + wy.w/gy.y))/size.y;",
                "return gy.x*(gx.x*texture(windowTexture,vec2(x.x,y.x))+gx.y*texture(windowTexture,vec2(x.y,y.x))) + gy.y*(gx.x*texture(windowTexture,vec2(x.x,y.y))+gx.y*texture(windowTexture,vec2(x.y,y.y)));",
            "}",

            "void main() {",
                "vec4 texelColor = smoothScaling ? sampleSmooth(vUV) : texture(windowTexture, vUV);",
                "outFragColor = vec4(texelColor.rgb, max(texelColor.a, noAlpha));",
            "}"
        );
    }
}
