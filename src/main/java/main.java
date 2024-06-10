package main.java;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import static java.lang.Math.floor;
import static java.lang.Math.ceil;


public class main {
    private static final int ACTUAL_XY_OFFSETS = 0x002;
    private static final int OFFSETS_ARE_LARGE = 0x001;
    private static final int GOT_A_SINGLE_SCALE = 0x008;
    private static final int GOT_AN_X_AND_Y_SCALE = 0x040;
    private static final int GOT_A_SCALE_MATRIX = 0x080;
    private static final int THERE_ARE_MORE_COMPONENTS =0x020;
    private static final int X_CHANGE_IS_SMALL = 0x02;
    private static final int Y_CHANGE_IS_SMALL = 0x04;
    private static final int X_CHANGE_IS_ZERO = 0x10;
    private static final int X_CHANGE_IS_POSITIVE = 0x10;
    private static final int Y_CHANGE_IS_ZERO = 0x20;
    private static final int Y_CHANGE_IS_POSITIVE = 0x20;
    private static final int FORMAT4 = 4;
    private static final int FORMAT6 = 6;
    private static final int FORMAT12 = 12;


    //MAIN API/////////////////////////////////////////////////////////////////////////////

    //Loading fonts
    public static Font loadFont(String filepath) {
        Font font = new Font();
        Path path = Paths.get(filepath);

        try {font.memory = Files.readAllBytes(path);}
        catch (IOException e) {throw new RuntimeException(e);}
        font.size = font.memory.length;

        if (!is_safe_offset(font, 0, 12)) return null;
        int scalerType = getu32(font, 0);
        if (scalerType != 0x00010000 && scalerType != 0x74727565) return null;

        int head = gettable(font, "head");
        if (head < 0) return null;
        if (!is_safe_offset(font, head, 54)) return null;
        font.unitsPerEm = getu16(font, head + 18);
        font.locaFormat = (short) geti16(font, head + 50);

        int hhea = gettable(font, "hhea");
        if (hhea < 0) return null;
        if (!is_safe_offset(font, hhea, 36)) return null;
        font.numLongHmtx = getu16(font, hhea + 34);

        return font;
    }

    //Maps Unicode code points to glyph indices by parsing the cmap table.
    public static int glyph_id(Font font, int charCode, int[] gid) {
        gid[0] = 0;
        int cmap = gettable(font, "cmap");
        if (cmap < 0 || !is_safe_offset(font, cmap, 4)) return -1;

        int numEntries = getu16(font, cmap + 2);
        int entryBase = cmap + 4;

        if (!is_safe_offset(font, entryBase, numEntries * 8)) return -1;

        for (int idx = 0; idx < numEntries; idx++) {
            int entry = entryBase + idx * 8;
            int type = getu32(font, entry);
            int table = cmap + getu32(font, entry + 4);

            if ((type == 0x00040000 || type == 0x00030001 || type == 0x00000312 || type == 0x00000301)) {
                int format = getu16(font, table);
                if ((type == 0x00040000 || type == 0x00030001) && format == 4 && processFormat4(font, table, charCode, gid)) {
                    return 0;
                } else if (type == 0x00040000 && format == 6 && processFormat6(font, table, charCode, gid)) {
                    return 0;
                } else if ((type == 0x00000312 || type == 0x00000301) && processFormat12(font, table, charCode, gid)) {
                    return 0;
                }
            }
        }
        return -1;
    }
    
    //When rendering multiple glyphs in a line, gmetrics() provide the information needed to place the next
    //glyph relative to the pen position, and updating the pen position crrectly.
    public static int gmetrics(JF jf, int[] gid, GMetrics metrics) {
        double xScale = jf.xScale / jf.font.unitsPerEm;
        double yScale = jf.yScale / jf.font.unitsPerEm;

        metrics.advanceWidth = 0;
        metrics.leftSideBearing = 0;
        metrics.yOffset = 0;
        metrics.minWidth = 0;
        metrics.minHeight = 0;

        int hmtx = gettable(jf.font, "hmtx");
        int offset;
        if (gid[0] < jf.font.numLongHmtx) {
            offset = hmtx + 4 * gid[0];
        } else {
            offset = hmtx + 4 * (jf.font.numLongHmtx - 1);
        }

        if (!is_safe_offset(jf.font, offset, 4)) return -1;
        int adv = getu16(jf.font, offset);
        int lsb = (gid[0] < jf.font.numLongHmtx) ? geti16(jf.font, offset + 2) : geti16(jf.font, offset + 2);

        metrics.advanceWidth = adv * xScale;
        metrics.leftSideBearing = lsb * xScale + jf.xOffset;

        int outline = outline_offset(jf.font, gid[0]);
        if (outline == 0) return 0;

        if (!is_safe_offset(jf.font, outline, 10)) return -1;
        int[] bbox = {geti16(jf.font, outline + 2), geti16(jf.font, outline + 4), geti16(jf.font, outline + 6), geti16(jf.font, outline + 8)};
        if (bbox[2] <= bbox[0] || bbox[3] <= bbox[1]) return -1;

        metrics.minWidth = (int) Math.ceil((bbox[2] - bbox[0]) * xScale) + 1;
        metrics.minHeight = (int) Math.ceil((bbox[3] - bbox[1]) * yScale) + 1;
        metrics.yOffset = (int) Math.floor(bbox[1] * yScale + jf.yOffset);

        return 0;
    }
    
    //Decodes the outline of a glyph.
    public static int decodeoutline(Font font, int offset, int recDepth, Outline outl) {
        if (!is_safe_offset(font, offset, 10)) return -1;
        int numContours = geti16(font, offset);
        offset += 10;

        if (numContours > 0) {
            return simple_outline(font, offset, numContours, outl);
        } else if (numContours < 0) {
            return compound_outline(font, offset, recDepth, outl);
        } else {
            return 0;
        }
    }

    //To actually render a glyph into a bitmap
    public static int renderfont(JF jf, int glyph, Image image) {
        double xScale = jf.xScale / jf.font.unitsPerEm;
        double yScale = jf.yScale / jf.font.unitsPerEm;

        int outline = outline_offset(jf.font, glyph);
        if (outline == 0) return 0;

        if (!is_safe_offset(jf.font, outline, 10)) return -1;
        int[] bbox = {geti16(jf.font, outline + 2), geti16(jf.font, outline + 4),
                geti16(jf.font, outline + 6), geti16(jf.font, outline + 8)};
        if (bbox[2] <= bbox[0] || bbox[3] <= bbox[1]) return -1;

        bbox[0] = (int) floor(bbox[0] * xScale + jf.xOffset);
        bbox[1] = (int) floor(bbox[1] * yScale + jf.yOffset);
        bbox[2] = (int) ceil(bbox[2] * xScale + jf.xOffset);
        bbox[3] = (int) ceil(bbox[3] * yScale + jf.yOffset);

        double[] transform = {
                xScale, 0.0, 0.0, yScale,
                jf.xOffset - bbox[0], jf.yOffset - bbox[1]
        };

        Outline outl = new Outline();
        if (decodeoutline(jf.font, outline, 0, outl) < 0) return -1;
        if (renderOutline(outl, transform, image) < 0) return -1;

        return 0;
    }



    public static class Font {
        public byte[] memory;
        public long size;
        public int unitsPerEm;
        public short locaFormat;
        public int numLongHmtx;
    }

    public static class JF {
        public Font font;
        public double xScale;  // The width/height of one em-square in pixels
        public double yScale;
        public double xOffset; // The horizontal/vertical offset to be applied before rendering to
        public double yOffset; // an image (Useful for subpixel-accurate positioning)
    }

    public static class LMetrics {
        public double ascender;
        public double descender;
        public double lineGap;
    }

    public static class GMetrics {
        public double advanceWidth;
        public double leftSideBearing;
        public int yOffset;
        public int minWidth;
        public int minHeight;

    }

    public static class Image {
        public byte[] pixels;
        public int width;
        public int height;
    }

    public static class Outline {
        public int numPoints;
        public int capPoints;
        public Point[] points;
        public int numCurves;
        public int capCurves;
        public Curve[] curves;
        public int numLines;
        public int capLines;
        public Line[] lines;
        public Outline() {
            numPoints = 0;
            capPoints = 64; 
            points = new Point[capPoints];
            for (int i = 0; i < capPoints; i++) {
                points[i] = new Point(0, 0);
            }

            numCurves = 0;
            capCurves = 64;
            curves = new Curve[capCurves];

            numLines = 0;
            capLines = 64;
            lines = new Line[capLines];
        }
    }

    public static class Point {
        public double x;
        public double y;

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

    }

    public static class Curve {
        public int beg;
        public int end;
        public int ctrl;

    public Curve(int beg, int end, int ctrl) {
            this.beg = beg;
            this.end = end;
            this.ctrl = ctrl;
        }}

    public static class Line {
        public int beg;
        public int end;

        public Line(int beg, int end) {
            this.beg = beg;
            this.end = end;
        }
    }

    private static class Raster {
        Cell[] cells;
        int width;
        int height;

        public Raster(int width, int height) {
            this.width = width;
            this.height = height;
            this.cells = new Cell[width * height];
            for (int i = 0; i < this.cells.length; i++) {
                this.cells[i] = new Cell();
            }
        }
    }

    private static class Cell {
        double area, cover;

        public Cell() {
            this.area = 0.0;
            this.cover = 0.0;
        }
    }


    //LOW LEVEL APIS//////////////////////////////////////////////////////////////
    //Helper-functions
    private static boolean processFormat12(Font font, int table, int charCode, int[] gid) {
        if (!is_safe_offset(font, table, 16) || getu16(font, table) != 12) return false;

        int len = getu32(font, table + 4);
        if (len < 16 || !is_safe_offset(font, table, len)) return false;

        int numEntries = getu32(font, table + 12);
        int base = table + 16;
        for (int i = 0; i < numEntries; i++) {
            int entryBase = base + i * 12;
            int firstCode = getu32(font, entryBase);
            int lastCode = getu32(font, entryBase + 4);
            if (charCode >= firstCode && charCode <= lastCode) {
                gid[0] = (charCode - firstCode) + getu32(font, entryBase + 8);
                return true;
            }
        }
        return false;
    }

    private static boolean processFormat4(Font font, int table, int charCode, int[] gid) {
        if (!is_safe_offset(font, table, 8)) return false;

        int segCountX2 = getu16(font, table + 6);
        if ((segCountX2 & 1) != 0 || segCountX2 == 0) return false;
        int segCount = segCountX2 / 2;
        int endCodes = table + 14;
        int startCodes = endCodes + segCountX2 + 2;
        int idDeltas = startCodes + segCountX2;
        int idRangeOffsets = idDeltas + segCountX2;

        if (!is_safe_offset(font, idRangeOffsets, segCountX2)) return false;
        for (int i = 0; i < segCount; i++) {
            int endCode = getu16(font, endCodes + 2 * i);
            if (endCode >= charCode) {
                int startCode = getu16(font, startCodes + 2 * i);
                if (startCode > charCode) return false;

                int idDelta = getu16(font, idDeltas + 2 * i);
                int idRangeOffset = getu16(font, idRangeOffsets + 2 * i);

                if (idRangeOffset == 0) {
                    gid[0] = (charCode + idDelta) & 0xFFFF;
                } else {
                    int offset = idRangeOffsets + 2 * i + idRangeOffset + 2 * (charCode - startCode);
                    if (!is_safe_offset(font, offset, 2)) return false;
                    gid[0] = (getu16(font, offset) + idDelta) & 0xFFFF;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean processFormat6(Font font, int table, int charCode, int[] gid) {
        if (!is_safe_offset(font, table, 10) || getu16(font, table) != 6) return false;

        int firstCode = getu16(font, table + 6);
        int entryCount = getu16(font, table + 8);
        int entryBase = table + 10;
        if (charCode < firstCode || charCode >= firstCode + entryCount) return false;

        gid[0] = getu16(font, entryBase + 2 * (charCode - firstCode));
        return true;
    }


    private static int simple_outline(Font font, int offset, int numContours, Outline outl) {
        if (!is_safe_offset(font, offset, numContours * 2 + 2)) {
            return -1;
        }

        int basePoint = outl.numPoints;
        int numPts = getu16(font, offset + (numContours - 1) * 2) + 1;

        if (numPts >= 0xFFFF || outl.numPoints > 0xFFFF - numPts) {
            return -1;
        }

        while (outl.capPoints < (basePoint + numPts)) {
            grow_points(outl);
        }


        int[] endPts = new int[numContours];
        byte[] flags = new byte[numPts];

        for (int i = 0; i < numContours; i++) {
            endPts[i] = getu16(font, offset);
            offset += 2;
        }

        offset += 2 + getu16(font, offset);
        int[] sf = new int[1];sf[0] = offset;
        if (simple_flags(font, sf, numPts, flags) < 0) {
            return -1;
        }
        offset = sf[0];

        if (simple_points(font, offset, numPts, flags, outl.points, basePoint) < 0) {
            return -1;
        }

        outl.numPoints += numPts;

        int beg = 0;
        for (int i = 0; i < numContours; i++) {
            int count = endPts[i] - beg + 1;
            byte[] fb = new byte[count];
            System.arraycopy(flags, beg, fb, 0, count);

            if (decode_contour(fb, basePoint + beg, count, outl) < 0) {
                return -1;
            }
            beg = endPts[i] + 1;
        }

        return 0;
    }

    private static int simple_flags(Font font, int[]  offset, int numPts, byte[] flags) {
        int off = offset[0] ;
        int i;
        byte value = 0, repeat = 0;
        for (i = 0; i < numPts; ++i) {
            if (repeat != 0) {
                --repeat;
            } else {
                if (!is_safe_offset(font, off, 1)) {
                    return -1;
                }
                value = (byte) getu8(font, off++);
                if ((value & 8) != 0) {
                    if (!is_safe_offset(font, off, 1)) {
                        return -1;
                    }
                    repeat = (byte) getu8(font, off++);
                }
            }
            flags[i] = value;
        }
        offset[0]  = (int) off;


        return 0;
    }

    private static int simple_points(Font font, int offset, int numPts, byte[] flags, Point[] points, int basePoint) {
        long accum = 0, value, bit;
        int i;

        for (i = 0; i < numPts; ++i) {
            if ((flags[i] & X_CHANGE_IS_SMALL) != 0) {
                if (!is_safe_offset(font, offset, 1)) {
                    return -1;
                }
                value = getu8(font, offset++);
                bit = (flags[i] & X_CHANGE_IS_POSITIVE) != 0 ? 1 : 0;
                accum -= (value ^ -bit) + bit;
            } else if ((flags[i] & X_CHANGE_IS_ZERO) == 0) {
                if (!is_safe_offset(font, offset, 2)) {
                    return -1;
                }
                accum += geti16(font, offset);
                offset += 2;
            }
            points[basePoint + i].x = accum;
        }

        accum = 0;
        for (i = 0; i < numPts; ++i) {
            if ((flags[i] & Y_CHANGE_IS_SMALL) != 0) {
                if (!is_safe_offset(font, offset, 1)) {
                    return -1;
                }
                value = getu8(font, offset++);
                bit = (flags[i] & Y_CHANGE_IS_POSITIVE) != 0 ? 1 : 0;
                accum -= (value ^ -bit) + bit;
            } else if ((flags[i] & Y_CHANGE_IS_ZERO) == 0) {
                if (!is_safe_offset(font, offset, 2)) {
                    return -1;
                }
                accum += geti16(font, offset);
                offset += 2;
            }
            points[basePoint + i].y = accum;
        }

        return 0;
    }

    static int compound_outline(Font font, int offset, int recDepth, Outline outl) {
        if (recDepth >= 4) return -1;

        double[] local = new double[6];
        int[] outline = new int[1];

        while (true) {
            Arrays.fill(local, 0);

            if (!is_safe_offset(font, offset, 4)) {
                return -1;
            }

            int flags = getu16(font, offset);
            int glyph = getu16(font, offset + 2);
            offset += 4;

            if ((flags & ACTUAL_XY_OFFSETS) == 0) {
                return -1;
            }

            if ((flags & OFFSETS_ARE_LARGE) != 0) {
                if (!is_safe_offset(font, offset, 4)) {
                    return -1;
                }
                local[4] = geti16(font, offset);
                local[5] = geti16(font, offset+ 2);
                offset += 4;
            } else {
                if (!is_safe_offset(font, offset, 2)) {
                    return -1;
                }
                local[4] = getu8(font, offset);
                local[5] = getu8(font, offset + 1);
                offset += 2;
            }

            if ((flags & GOT_A_SINGLE_SCALE) != 0) {
                if (!is_safe_offset(font, offset, 2)) {
                    return -1;
                }
                local[0] = local[3] = geti16(font, offset) / 16384.0;
                offset += 2;
            } else if ((flags & GOT_AN_X_AND_Y_SCALE) != 0) {
                if (!is_safe_offset(font, offset, 4)) {
                    return -1;
                }
                local[0] = geti16(font, offset) / 16384.0;
                local[3] = geti16(font, offset + 2) / 16384.0;
                offset += 4;
            } else if ((flags & GOT_A_SCALE_MATRIX) != 0) {
                if (!is_safe_offset(font, offset, 8)) {
                    return -1;
                }
                local[0] = geti16(font, offset) / 16384.0;
                local[1] = geti16(font, offset + 2) / 16384.0;
                local[2] = geti16(font, offset + 4) / 16384.0;
                local[3] = geti16(font, offset + 6) / 16384.0;
                offset += 8;
            } else {
                local[0] = local[3] = 1.0;
            }

            outline[0] = outline_offset(font, glyph );


            if (outline[0] != 0) {
                int basePoint = outl.numPoints;

                if (decodeoutline(font, outline[0], recDepth + 1, outl) < 0) {
                    return -1;
                }

                //transform points
                Point pt;
                for (int i = 0; i < (outl.numPoints - basePoint) ; i++) {
                    pt = outl.points[i + basePoint];
                    outl.points[i + basePoint] = new Point(pt.x * local[0] + pt.y * local[2] + local[4], pt.x * local[1] + pt.y * local[3] + local[5]);
                }


            }

            if ((flags & THERE_ARE_MORE_COMPONENTS) == 0) {
                break;
            }
        }

        return 0;
    }

    private static int decode_contour(byte[] fb, int basePoint, int count, Outline outl) {
        int i;
        int looseEnd, beg, ctrl, center, cur;
        boolean gotCtrl;
        ctrl = 0;

        if (count < 2) return 0;

        assert basePoint <= 0xFFFF - count;

        if ((fb[0] & 1) != 0) {
            looseEnd = basePoint++;
            fb = Arrays.copyOfRange(fb, 1, fb.length);
            --count;
        } else if ((fb[count - 1] & 1) != 0) {
            looseEnd = basePoint + --count;
        } else {
            if (outl.numPoints >= outl.capPoints && grow_points(outl) < 0) {
                return -1;
            }
            looseEnd = outl.numPoints;
            outl.points[outl.numPoints++] = midpoint(outl.points[basePoint], outl.points[basePoint + count - 1]);
        }
        beg = looseEnd;
        gotCtrl = false;
        for (i = 0; i < count; ++i) {
            cur = basePoint + i;
            if ((fb[i] & 1) != 0) {
                if (gotCtrl) {
                    if (outl.numCurves >= outl.capCurves && grow_curves(outl) < 0) {
                        return -1;
                    }
                    outl.curves[outl.numCurves++] = new Curve(beg, cur, ctrl);
                    gotCtrl = false;
                } else {
                    if (outl.numLines >= outl.capLines && grow_lines(outl) < 0) {
                        return -1;
                    }
                    outl.lines[outl.numLines++] = new Line(beg, cur);
                }
                beg = cur;
            } else if (gotCtrl) {
                if (outl.numCurves >= outl.capCurves && grow_curves(outl) < 0) {
                    return -1;
                }
                center = outl.numPoints;
                if (center == outl.capPoints && grow_points(outl) < 0) {
                    return -1;
                }
                outl.points[outl.numPoints++] = midpoint(outl.points[ctrl], outl.points[cur]);
                outl.curves[outl.numCurves++] = new Curve(beg, center, ctrl);
                beg = center;
                ctrl = cur;
            } else {
                ctrl = cur;
                gotCtrl = true;
            }
        }
        if (gotCtrl) {
            if (outl.numCurves >= outl.capCurves && grow_curves(outl) < 0) {
                return -1;
            }
            outl.curves[outl.numCurves++] = new Curve(beg, looseEnd, ctrl);
        } else if (beg != looseEnd) {
            if (outl.numLines >= outl.capLines && grow_lines(outl) < 0) {
                return -1;
            }
            outl.lines[outl.numLines++] = new Line(beg, looseEnd);
        }

        return 0;
    }

    private static int grow_curves(Outline outl) {
        Curve[] newCurves;
        int cap;
        assert outl.capCurves > 0;
        if (outl.capCurves > 0xFFFF / 2) {
            return -1;
        }
        cap = 2 * outl.capCurves;
        newCurves = new Curve[cap];
        System.arraycopy(outl.curves, 0, newCurves, 0, outl.numCurves);
        outl.curves = newCurves;
        outl.capCurves = cap;
        return 0;
    }

    private static int grow_lines(Outline outl) {
        Line[] newLines;
        int cap;
        assert outl.capLines > 0;
        if (outl.capLines > 0xFFFF / 2) {
            return -1;
        }
        cap = 2 * outl.capLines;
        newLines = new Line[cap];
        System.arraycopy(outl.lines, 0, newLines, 0, outl.numLines);
        outl.lines = newLines;
        outl.capLines = cap;
        return 0;
    }

    private static int grow_points(Outline outl) {
        Point[] newPoints;
        int cap;
        assert outl.capPoints > 0;
        if (outl.capPoints > 0xFFFF / 2) {
            return -1;
        }
        cap = 2 * outl.capPoints;
        newPoints = new Point[cap];
        for (int i = 0; i < cap; i++) {
            newPoints[i] = new Point(0, 0); // Initialize points
        }
        System.arraycopy(outl.points, 0, newPoints, 0, outl.numPoints);
        outl.points = newPoints;
        outl.capPoints = cap;
        return 0;
    }

    public static void drawLine(Raster buf, Point origin, Point goal) {
        Point delta = new Point(goal.x - origin.x, goal.y - origin.y);
        Point nextCrossing = new Point(0,0);
        Point crossingIncr = new Point(0,0);
        double halfDeltaX;
        double prevDistance = 0.0, nextDistance;
        double xAverage, yDifference;
        int step, numSteps = 0;
        Cell cell;
        int[] pixel = new int[2];
        int[] dir = new int[2];

        dir[0] = (int) Math.signum(delta.x);
        dir[1] = (int) Math.signum(delta.y);

        if (dir[1] == 0) return;

        crossingIncr.x = dir[0] != 0 ? Math.abs(1.0 / delta.x) : 1.0;
        crossingIncr.y = Math.abs(1.0 / delta.y);

        if (dir[0] == 0) {
            pixel[0] = (int) floor(origin.x);


            nextCrossing.x = 100.0;
        } else {
            if (dir[0] > 0) {
                pixel[0] = (int) floor(origin.x);
                nextCrossing.x = (origin.x - pixel[0]) * crossingIncr.x;
                nextCrossing.x = crossingIncr.x - nextCrossing.x;
                numSteps += ceil(goal.x) - floor(origin.x) - 1;
            } else {
                pixel[0] = (int) (ceil(origin.x) - 1);
                nextCrossing.x = (origin.x - pixel[0]) * crossingIncr.x;
                numSteps += ceil(origin.x) - floor(goal.x) - 1;
            }
        }

        if (dir[1] > 0) {
            pixel[1] = (int) floor(origin.y);
            nextCrossing.y = (origin.y - pixel[1]) * crossingIncr.y;
            nextCrossing.y = crossingIncr.y - nextCrossing.y;
            numSteps += ceil(goal.y) - floor(origin.y) - 1;
        } else {
            pixel[1] = (int) (ceil(origin.y) - 1);
            nextCrossing.y = (origin.y - pixel[1]) * crossingIncr.y;
            numSteps += ceil(origin.y) - floor(goal.y) - 1;
        }

        nextDistance = Math.min(nextCrossing.x, nextCrossing.y);
        halfDeltaX = 0.5 * delta.x;

        for (step = 0; step < numSteps; ++step) {
            xAverage = origin.x + (prevDistance + nextDistance) * halfDeltaX;
            yDifference = (nextDistance - prevDistance) * delta.y;
            cell = buf.cells[pixel[1] * buf.width + pixel[0]];
            cell.cover += yDifference;
            xAverage -= pixel[0];
            cell.area += (1.0 - xAverage) * yDifference;
            prevDistance = nextDistance;
            boolean alongX = nextCrossing.x < nextCrossing.y;
            pixel[0] += alongX ? dir[0] : 0;
            pixel[1] += alongX ? 0 : dir[1];
            nextCrossing.x += alongX ? crossingIncr.x : 0.0;
            nextCrossing.y += alongX ? 0.0 : crossingIncr.y;
            nextDistance = Math.min(nextCrossing.x, nextCrossing.y);
        }

        xAverage = origin.x + (prevDistance + 1.0) * halfDeltaX;
        yDifference = (1.0 - prevDistance) * delta.y;
        cell = buf.cells[pixel[1] * buf.width + pixel[0]];
        cell.cover += yDifference;
        xAverage -= pixel[0];
        cell.area += (1.0 - xAverage) * yDifference;
    }

    public static int renderOutline(Outline outl, double[] transform, Image image) {
        Cell[] cells;
        Raster buf;
        int numPixels;

        numPixels = image.width * image.height;
        cells = new Cell[numPixels];
        for (int i = 0; i < numPixels; i++) {
            cells[i] = new Cell();
        }
        buf = new Raster(image.width, image.height);
        buf.cells = cells;

        transformPoints(outl.numPoints, outl.points, transform);
        clipPoints(outl.numPoints, outl.points, image.width, image.height);


        for (int i = 0; i < outl.numCurves; ++i) {
            if (tesselateCurve(outl.curves[i], outl) < 0) {
                return -1;
            }
        }


        for (int i = 0; i < outl.numLines; ++i) {
            Line line = outl.lines[i];
            Point origin = outl.points[line.beg];
            Point goal = outl.points[line.end];
            drawLine(buf, origin, goal);
        }

        Cell cell;
        double accum = 0.0, value;
        int num = buf.width * buf.height;

        for (int i = 0; i < num; ++i) {
            cell = buf.cells[i];
            value = Math.abs(accum + cell.area);
            value = Math.min(value, 1.0);
            value = value * 255.0 + 0.5;
            image.pixels[i] = (byte) value;
            accum += cell.cover;
        }

        return 0;
    }

    private static int outline_offset(Font font, int gid) {
        int base, offset;
        long thisOffset;
        long nextOffset;

        int loca = gettable(font, "loca");
        int glyf = gettable(font, "glyf");


        if (font.locaFormat == 0) {
            base = loca + 2 * gid;

            if (!is_safe_offset(font, base, 4))
                System.exit(0);

            thisOffset = 2 * getu16(font, base);
            nextOffset = 2 * getu16(font, base + 2);
        } else {
            base = loca + 4 * gid;

            if (!is_safe_offset(font, base, 8))
                System.exit(0);

            thisOffset = getu32(font, base);
            nextOffset = getu32(font, base + 4);
        }

        return thisOffset == nextOffset ? 0 : (int) (glyf + thisOffset);
    }

    static int gettable(Font font, String tag) {
        int offset;
        int numTables = getu16(font, 4);
        if (!is_safe_offset(font, 12, numTables * 16))
            System.exit(0);
        byte[] tagBytes = new byte[4];
        for (int i = 0; i < numTables; i++) {
            long entryOffset = 12 + i * 16;
            System.arraycopy(font.memory,(int) entryOffset,tagBytes, 0, 4);
            String tableTag = new String(tagBytes);
            if (tableTag.equals(tag)) {
                offset = getu32(font, entryOffset + 8);
                return offset;
            }
        }
        System.out.println("Table not found");
        return -1;
    }



    //math-functions
    static int getu16(Font font, int offset) {
        return ByteBuffer.wrap(font.memory , offset,2).getShort() & 0xFFFF;
    }
    static int getu32(Font font, long offset) {
        return ByteBuffer.wrap(font.memory , (int) offset,4).getInt() ;

    }
    static int getu8(Font font, int offset) {
        return font.memory[offset] & 0xFF;
    }
    static int geti16(Font font, int offset) {
        return (short) getu16(font, offset);
    }

    static Point midpoint(Point p1, Point p2) {
        return new Point((p1.x + p2.x) / 2.0, (p1.y + p2.y) / 2.0);
    }

    static boolean is_safe_offset(Font font, int offset, int margin) {
        if (!(offset >= 0 && margin >= 0 && offset <= font.size - margin)) {
            System.out.println("is_safe_offset is not safe!"); return false;
        }
        return true;
    }

    public static void transformPoints(int numPts, Point[] points, double[] trf) {
        for (int i = 0; i < numPts; ++i) {
            Point pt = points[i];
            points[i] = new Point(
                    pt.x * trf[0] + pt.y * trf[2] + trf[4],
                    pt.x * trf[1] + pt.y * trf[3] + trf[5]
            );
        }
    }
    public static void clipPoints(int numPts, Point[] points, int width, int height) {
        for (int i = 0; i < numPts; ++i) {
            Point pt = points[i];

            if (pt.x < 0.0) {
                points[i].x = 0.0;
            }
            if (pt.x >= width) {
                points[i].x = Math.nextAfter(width, 0.0);
            }
            if (pt.y < 0.0) {
                points[i].y = 0.0;
            }
            if (pt.y >= height) {
                points[i].y = Math.nextAfter(height, 0.0);
            }
        }
    }
    public static int tesselateCurve(Curve curve, Outline outl) {
        final int STACK_SIZE = 10;
        Curve[] stack = new Curve[STACK_SIZE];
        int top = 0;

        while (true) {
            if (isFlat(outl, curve) || top >= STACK_SIZE) {


                if (outl.numLines >= outl.capLines && grow_lines(outl) < 0) {
                    return -1;
                }
                outl.lines[outl.numLines++] = new Line(curve.beg, curve.end);
                if (top == 0) break;
                curve = stack[--top];
            } else {
                int ctrl0 = outl.numPoints;
                if (outl.numPoints >= outl.capPoints && grow_points(outl) < 0) {
                    return -1;
                }
                outl.points[ctrl0] = midpoint(outl.points[curve.beg], outl.points[curve.ctrl]);
                outl.numPoints++;

                int ctrl1 = outl.numPoints;
                if (outl.numPoints >= outl.capPoints && grow_points(outl) < 0) {
                    return -1;
                }
                outl.points[ctrl1] = midpoint(outl.points[curve.ctrl], outl.points[curve.end]);
                outl.numPoints++;

                int pivot = outl.numPoints;
                if (outl.numPoints >= outl.capPoints && grow_points(outl) < 0) {
                    return -1;
                }
                outl.points[pivot] = midpoint(outl.points[ctrl0], outl.points[ctrl1]);
                outl.numPoints++;

                stack[top++] = new Curve(curve.beg, pivot, ctrl0);
                curve = new Curve(pivot, curve.end, ctrl1);
            }
        }
        return 0;
    }
    public static boolean isFlat(Outline outl, Curve curve) {
        final double maxArea2 = 2.0;
        Point a = outl.points[curve.beg];
        Point b = outl.points[curve.ctrl];
        Point c = outl.points[curve.end];
        Point g = new Point(b.x - a.x, b.y - a.y);
        Point h = new Point(c.x - a.x, c.y - a.y);
        double area2 = Math.abs(g.x * h.y - h.x * g.y);
        return area2 <= maxArea2;
    }

    // a utf8ToUtf32 function GENERATED BY CHATGPT
    public static int utf8_to_utf32(byte[] utf8, int[] utf32, int max) {
        int c;
        int i = 0;
        int index = 0;
        --max;

        while (index < utf8.length) {
            if (i >= max) {
                return 0;
            }

            if ((utf8[index] & 0x80) == 0) {
                utf32[i++] = utf8[index++] & 0xFF;
            } else if ((utf8[index] & 0xE0) == 0xC0) {
                c = (utf8[index++] & 0x1F) << 6;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                utf32[i++] = c + (utf8[index++] & 0x3F);
            } else if ((utf8[index] & 0xF0) == 0xE0) {
                c = (utf8[index++] & 0x0F) << 12;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                c += (utf8[index++] & 0x3F) << 6;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                utf32[i++] = c + (utf8[index++] & 0x3F);
            } else if ((utf8[index] & 0xF8) == 0xF0) {
                c = (utf8[index++] & 0x07) << 18;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                c += (utf8[index++] & 0x3F) << 12;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                c += (utf8[index++] & 0x3F) << 6;
                if ((utf8[index] & 0xC0) != 0x80) return 0;
                c += (utf8[index++] & 0x3F);
                if ((c & 0xFFFFF800) == 0xD800) return 0;
                utf32[i++] = c;
            } else {
                return 0;
            }
        }

        utf32[i] = 0;
        return i;
    }


}
