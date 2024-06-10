package main.java;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

import main.java.main.LMetrics;
import main.java.main.JF;


import static main.java.main.geti16;
import static main.java.main.is_safe_offset;

public class demo1 {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 880;
    private static String ttfFilePath;
    private static String txtFilePath;
    private static String inputText;

    public static void main(String[] args) {

        ttfFilePath = "src/main/resources/FiraGO-Regular_extended_with_NotoSansEgyptianHieroglyphs-Regular.ttf";
        txtFilePath = "src/main/resources/test.utf8";

        byte[] buffer = new byte[WIDTH * HEIGHT * 3];
        Arrays.fill(buffer, (byte) 255);

        main.JF jf = new JF();
        jf.xScale = 32;
        jf.yScale = 32;


        jf.font = main.loadFont(ttfFilePath);
        if (jf.font == null) {
            System.out.println("font initialization failed"); return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(txtFilePath))) {
            {
                LMetrics metrics = new LMetrics();
                double factor;
                int hhea = main.gettable(jf.font, "hhea");
                if (!is_safe_offset(jf.font, hhea, 36))
                    return;
                factor = jf.yScale / jf.font.unitsPerEm;
                metrics.ascender = geti16(jf.font, hhea + 4) * factor;
                metrics.descender = geti16(jf.font, hhea + 6) * factor;
                metrics.lineGap = geti16(jf.font, hhea + 8) * factor;

                int[] y = new int[1];
                y[0] = (int) (40 + metrics.ascender);

                String text;
                while ((text = br.readLine()) != null) {

                    if (text.endsWith("\n")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    renderTextToImage(buffer, jf, text, 40, y, WIDTH, HEIGHT);
                    y[0] += metrics.ascender + metrics.descender + metrics.lineGap + 20; // Add extra space for line break
                }
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            savePNG(Paths.get("output.png").toString(), buffer, WIDTH, HEIGHT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }



    public static void savePNG(String filename, byte[] buffer, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Fill the image with the data from the buffer
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = buffer[index] & 0xFF;
                int g = buffer[index + 1] & 0xFF;
                int b = buffer[index + 2] & 0xFF;
                int rgb = (r << 16) | (g << 8) | b;
                image.setRGB(x, y, rgb);
                index += 3;
            }
        }

        // Write the image to a file
        File file = new File(filename);
        if (!ImageIO.write(image, "png", file)) {
            throw new IOException("Could not write image to file: " + filename);
        }
    }



    public static void renderTextToImage(byte[] buffer, JF jf, String text, int j, int[] y, int width, int height) {
        int[] codepoints = new int[256];
        int n = main.utf8_to_utf32(text.getBytes(StandardCharsets.UTF_8), codepoints, codepoints.length);

        int startX = j;
        for (int i = 0; i < n; i++) {
            int[] gid = new int[1];
            if (main.glyph_id(jf.font,codepoints[i], gid)< 0) continue;

            main.GMetrics mtx = new main.GMetrics();
            if (main.gmetrics(jf,gid, mtx) < 0) continue;

            main.Image img = new main.Image();
            img.width  = (mtx.minWidth + 3) & ~3;
            img.height = mtx.minHeight;
            img.pixels = new byte[img.width * img.height];

            if (main.renderfont(jf,gid[0], img) < 0) continue;

            renderGlyphToBuffer(buffer, img,(int)( j + mtx.leftSideBearing), y[0] - mtx.yOffset, width, height, mtx);

            j += mtx.advanceWidth;

            if (j > width - 40) { // Wrap line if exceeds width
                j = startX;
                y[0] += mtx.minHeight + 20; // Move to next line
            }
        }
    }

    public static void renderGlyphToBuffer(byte[] buffer, main.Image img, int d, int y, int width, int height, main.GMetrics mtx) {
        int segments = 20; // Number of segments for Bézier curve decomposition
        float[] points = new float[segments * 2 + 2];

        byte[] pixels = img.pixels;
        for (int j = 0; j < img.height; j++) {
            for (int k = 0; k < img.width; k++) {
                byte pixelValue = pixels[j * img.width + k];
                if (pixelValue != 0) {
                    int px = (int) (d + k);
                    int py = y - j;
                    if (px >= 0 && px < width && py >= 0 && py < height) {
                        // Blend the pixel value with the background (white)
                        int index = (py * width + px) * 3;
                        buffer[index] = (byte)((255 - pixelValue) * (buffer[index] & 0xFF) / 255); // R
                        buffer[index + 1] = (byte)((255 - pixelValue) * (buffer[index + 1] & 0xFF) / 255); // G
                        buffer[index + 2] = (byte)((255 - pixelValue) * (buffer[index + 2] & 0xFF) / 255); // B
                    }
                }
            }
        }
    }



}
