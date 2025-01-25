package nrl;

// Note: This codebase is a modified version of the actual face detection
// code available at:
// https://github.com/bytedeco/javacv/blob/master/samples/DeepLearningFaceDetection.java
// It uses pre-trained Resnet model in OpenCV to detect faces and foreground.

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


class TilesOfFGAndBG {
    volatile String fgTiles = "";
    volatile String bgTiles = "";

    public String getFGTiles() {
        return this.fgTiles;
    }

    public String getBGTiles() {
        return this.bgTiles;
    }

    public void setFGTiles(String tiles) {
        this.fgTiles += tiles;
    }

    public void setBGTiles(String tiles) {
        this.bgTiles += tiles;
    }

    public void reset(){
        this.fgTiles = "";
        this.bgTiles = "";
    }
}


public class ForegroundDetection implements Runnable{
    private String modelFile = "src/main/java/nrl/model/deploy.prototxt";
    private String weightsFile = "src/main/java/nrl/model/res10_300x300_ssd_iter_140000.caffemodel";

    Net model;
    TileDims tiles;
    TilesOfFGAndBG tilesString;
    float minConfidence;
    public ForegroundDetection(TileDims tileDims, TilesOfFGAndBG tiles, float minConfidence){
        this.model = Dnn.readNetFromCaffe(this.modelFile, this.weightsFile);
        this.tiles = tileDims;
        this.tilesString = tiles;
        this.minConfidence = minConfidence;
    }

    public ArrayList<Integer> getTile(double x, double y, double w, double h) {
        HashMap<Integer, int[]> tilehashmap = tiles.getTileForFGDetectionHashMap().get("1280x720");
        ArrayList<Integer> tilesWithFaces = new ArrayList<>();

        for (int d = 2; d < 17; d++) {
            if (x < tilehashmap.get(d - 2)[0] & y < tilehashmap.get(d - 2)[1] & y > (tilehashmap.get(d - 2)[1] - tilehashmap.get(d - 2)[3]) & x > (tilehashmap.get(d - 2)[0] - tilehashmap.get(d - 2)[2]) & tilehashmap.get(d - 2)[4] == 0) {
                tilesWithFaces.add(d - 2); // Select the tile which contains the top left corner of the BBox
            }
            boolean b = (tilehashmap.get(d - 2)[1] > y) & ((tilehashmap.get(d - 2)[1] - tilehashmap.get(d - 2)[3]) < (y + h)) & (((d - 5) % 4) != 0); // -5 for tile indexing starting with 2
            if ((tilehashmap.get(d - 2)[0] < (x + w) & tilehashmap.get(d - 2)[0] > x) & b) { //((tilehashmap.get(d-2)[1] > y) & ((tilehashmap.get(d-2)[1] - tilehashmap.get(d-2)[3]) < (y + h)) & ((((d-2) - 5) % 4) != 0)) {
                tilesWithFaces.add((d - 2) + 4);   // Select the tile in the right of current tile if BBox extends into it
            }
            if ((tilehashmap.get(d - 2)[1] < (y + h)) & (tilehashmap.get(d - 2)[1] > y) & (tilehashmap.get(d - 2)[0] > x) & ((tilehashmap.get(d - 2)[0] - tilehashmap.get(d - 2)[2]) < (x + w)) & (d < 14)) { // 14 for tile indexing starting with 2
                tilesWithFaces.add((d - 2) + 4);   // Select the tile at the bottom of current tile if BBox extends into it
            }
        }
        return tilesWithFaces;
    }

    public Set<Integer> tilesWithFaces(Mat detections, int screenWidth, int screenHeight) {
        Set<Integer> tilesWithFaces = new HashSet<Integer>() {
        };
        int numOfFaces = detections.size(2);
        for (int face = 0; face < numOfFaces; face++) {
            double conf = detections.get(new int[]{0, 0, face, 2})[0]; // returns confidence score
            if (conf > this.minConfidence) {
                double face_x = detections.get(new int[]{0, 0, face, 3})[0] * screenWidth;
                double face_y = detections.get(new int[]{0, 0, face, 4})[0] * screenHeight;
                double face_w = detections.get(new int[]{0, 0, face, 5})[0] * screenWidth - face_x;
                double face_h = detections.get(new int[]{0, 0, face, 6})[0] * screenHeight - face_y;
                tilesWithFaces.addAll(this.getTile(face_x, face_y, face_w, face_h));
            }
        }
        return tilesWithFaces;
    }

    public Mat inferFromImage(Mat image){
        Mat blob = Dnn.blobFromImage(image, 1.0, new Size(600, 600), new Scalar(104.0, 177.0, 123.0));
        this.model.setInput(blob);
        Mat detections = this.model.forward();
        return detections;
    }

    public Mat captureScreen() throws AWTException {
        Robot robot = new Robot();
        Rectangle screenSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage screenImage = robot.createScreenCapture(screenSize);

        int h = screenImage.getHeight();
        int w = screenImage.getWidth();
        Mat mat = new Mat(screenImage.getHeight(), screenImage.getWidth(), CvType.CV_8UC3);
        int[] pix = ((DataBufferInt) screenImage.getRaster().getDataBuffer()).getData();

        byte[] data = new byte[w * h * 3];
        int indx = 0;

        for (int p : pix) {
            data[indx++] = (byte) ((p >> 16) & 0xFF);
            data[indx++] = (byte) ((p >> 8) & 0xFF);
            data[indx++] = (byte) (p & 0xFF);
        }
        mat.put(0, 0, data);
        return mat;
    }

    public void infer () throws AWTException {
        this.tilesString.reset();
        Mat screenCapture = this.captureScreen();
        Mat faces = this.inferFromImage(screenCapture);
        Set<Integer> foregroundTiles = this.tilesWithFaces(faces, screenCapture.width(), screenCapture.height());
//        System.out.println("Foreground Tiles: " + foregroundTiles + " " + foregroundTiles.isEmpty());

        if (foregroundTiles.isEmpty()){
            // Here tile indices start with 0 to match with client side's OpenCV indices.
            // We always keep tile 00 in foreground and never consider it for shifting
            // as it may break video playback because GPAC requires it.
            tilesString.setFGTiles("01,02,03,04,05,06,07,08,");
            tilesString.setBGTiles("09,10,11,12,13,14,15,");
//            tilesString.setFGTiles("01,02,03,04,05,06,07,08,");
//            tilesString.setBGTiles("09,10,11,12,13,14,15");
        } else {
            Set<Integer> tilesForBody = new HashSet<>();
            for (int i = 1; i < 16; i++) {
                if ((foregroundTiles.contains(i) == true) & (tilesForBody.contains(i) == false)) {
                    tilesString.setFGTiles(String.format("%02d", i) + ",");
                    if((i + 4) < 16) {
                        tilesString.setFGTiles(String.format("%02d", i + 4) + ","); // adding one extra tile to body +4th tile
                        tilesForBody.add(i + 4);
                    }
                } else {
                    if (tilesForBody.contains(i) == false) {
                        tilesString.setBGTiles(String.format("%02d", i) + ",");
                    }
                }
            }
        }
//        System.out.println("Set FG and BG Strings");
    }

    @Override
    public void run() {
        try {
            long start = System.currentTimeMillis();
            infer();
            long end = System.currentTimeMillis();
//            System.out.println("[FG Detection Time] "+(end-start));
        } catch (AWTException e) {
            throw new RuntimeException(e);
        }
    }
}
