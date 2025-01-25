package nrl;

import java.util.HashMap;

class TileDims {
    private HashMap<String, HashMap> tilesForFGDetection = new HashMap<>();

    public TileDims(){
        HashMap<Integer, int[]> dims = new HashMap<Integer, int[]>();

        ////////// For FG Detection ///////////////////////////////////////
        dims.put(0, new int[]{320, 192, 320, 192, 0});
        dims.put(1, new int[]{640, 192, 320, 192, 0});
        dims.put(2, new int[]{960, 192, 320, 192, 0});
        dims.put(3, new int[]{1280, 192, 320, 192, 0});
        dims.put(4, new int[]{320, 384, 320, 192, 0});
        dims.put(5, new int[]{640, 384, 320, 192, 0});
        dims.put(6, new int[]{960, 384, 320, 192, 0});
        dims.put(7, new int[]{1280, 384, 320, 192, 0});
        dims.put(8, new int[]{320, 576, 320, 192, 0});
        dims.put(9, new int[]{640, 576, 320, 192, 0});
        dims.put(10, new int[]{960, 576, 320, 192, 0});
        dims.put(11, new int[]{1280, 576, 320, 192, 0});
        dims.put(12, new int[]{320, 720, 320, 144, 0});
        dims.put(13, new int[]{640, 720, 320, 144, 0});
        dims.put(14, new int[]{960, 720, 320, 144, 0});
        dims.put(15, new int[]{1280, 720, 320, 144, 0});

        // for 960x540
//        dims.put(0, new int[]{192, 128, 192, 128, 0});
//        dims.put(1, new int[]{448, 128, 256, 128, 0});
//        dims.put(2, new int[]{704, 128, 256, 128, 0});
//        dims.put(3, new int[]{960,128, 256, 128, 0});
//        dims.put(4, new int[]{192, 256, 192, 128, 0});
//        dims.put(5, new int[]{448, 256, 256, 128, 0});
//        dims.put(6, new int[]{704, 256, 256, 128, 0});
//        dims.put(7, new int[]{960, 256, 256, 128, 0});
//        dims.put(8, new int[]{192, 386, 192, 130, 0});
//        dims.put(9, new int[]{448, 386, 256, 130, 0});
//        dims.put(10, new int[]{704, 386, 256, 130, 0});
//        dims.put(11, new int[]{960, 386, 256, 130, 0});
//        dims.put(12, new int[]{192, 540, 192, 154, 0});
//        dims.put(13, new int[]{448, 540, 256, 154, 0});
//        dims.put(14, new int[]{704, 540, 256, 154, 0});
//        dims.put(15, new int[]{960, 540, 256, 154, 0});
        tilesForFGDetection.put("1280x720", dims);
    }

    public HashMap<String, HashMap> getTileForFGDetectionHashMap(){
        return tilesForFGDetection;
    }
}
