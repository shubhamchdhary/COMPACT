package org.example;

import java.util.HashMap;

public class TileDims {
    private HashMap<String, HashMap> tiles = new HashMap<>();
    private HashMap<String, HashMap> tilesForFGDetection = new HashMap<>();

    public TileDims() {

        ///////// For stitching ////////////////////////////////////////////
        // For video with resolution 1280x720
        HashMap<Integer, int[]> dims = new HashMap<Integer, int[]>();
        // Each value is a tuple having x1, y1, x2, y2 of a tile indicated by the keys
//        dims.put(2, new int[]{0, 0, 320, 192});
//        dims.put(3, new int[]{320, 0, 640, 192});
//        dims.put(4, new int[]{640, 0, 960, 192});
//        dims.put(5, new int[]{960, 0, 1280, 192});
//        dims.put(6, new int[]{0, 192, 320, 384});
//        dims.put(7, new int[]{320, 192, 640, 384});
//        dims.put(8, new int[]{640, 192, 960, 384});
//        dims.put(9, new int[]{960, 192, 1280, 384});
//        dims.put(10, new int[]{0, 384, 320, 576});
//        dims.put(11, new int[]{320, 384, 640, 576});
//        dims.put(12, new int[]{640, 384, 960, 576});
//        dims.put(13, new int[]{960, 384, 1280, 576});
//        dims.put(14, new int[]{0, 576, 320, 720});
//        dims.put(15, new int[]{320, 576, 640, 720});
//        dims.put(16, new int[]{640, 576, 960, 720});
//        dims.put(17, new int[]{960, 576, 1280, 720});

        dims.put(0, new int[]{0, 0, 320, 192});
        dims.put(1, new int[]{320, 0, 640, 192});
        dims.put(2, new int[]{640, 0, 960, 192});
        dims.put(3, new int[]{960, 0, 1280, 192});
        dims.put(4, new int[]{0, 192, 320, 384});
        dims.put(5, new int[]{320, 192, 640, 384});
        dims.put(6, new int[]{640, 192, 960, 384});
        dims.put(7, new int[]{960, 192, 1280, 384});
        dims.put(8, new int[]{0, 384, 320, 576});
        dims.put(9, new int[]{320, 384, 640, 576});
        dims.put(10, new int[]{640, 384, 960, 576});
        dims.put(11, new int[]{960, 384, 1280, 576});
        dims.put(12, new int[]{0, 576, 320, 720});
        dims.put(13, new int[]{320, 576, 640, 720});
        dims.put(14, new int[]{640, 576, 960, 720});
        dims.put(15, new int[]{960, 576, 1280, 720});
        tiles.put("1280x720", dims);
    }

    public int[] getTileDims(String video_resolution, int tile_index){
        return (int[]) this.tiles.get(video_resolution).get(tile_index);
    }

}
