package nrl;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import org.opencv.core.Core;

import java.util.concurrent.LinkedBlockingQueue;


class ScreenCapture implements Runnable {
    EvictingQueue receiverQueue;
    int iterations;
    ForegroundDetection fgDetector;
    TilesOfFGAndBG tiles;
    Scheduler scheduler;
    ArrayList qps;

    public ScreenCapture(EvictingQueue p_queue, int iterations, ForegroundDetection fgDetector, TilesOfFGAndBG tiles, Scheduler scheduler) throws IOException {
        this.receiverQueue = p_queue;
        this.iterations = iterations;
        this.fgDetector = fgDetector;
        this.scheduler = scheduler;
        this.tiles = tiles;
        this.qps = this.readQPFile("src/main/java/nrl/QP_walk_Video5.txt");
//        System.out.println("QPs: " + this.qps.get(0) + " " + this.qps.size());
    }

    public ArrayList readQPFile(String filename) throws IOException {
        // reads QPs logged from our scheduler from file to replay
        ArrayList<String> lines = (ArrayList<String>) Files.readAllLines(new File(filename).toPath(), Charset.forName("utf-8"));
        return lines;
    }

    public void captureScreen() throws IOException, InterruptedException {
        boolean coldStartPhase = true;
        for (int i = 0; i < iterations; i++) {
            String s = new String("out" + i);
            try {
                // Detecting foreground
                Thread fgDetectionThread = new Thread(this.fgDetector);
                fgDetectionThread.start(); // returns immediately

                // For generating both fg and bg together
                Process p;
                p = Runtime.getRuntime().exec("bash src/main/java/nrl/capture.sh 25 4 src/main/java/nrl/hevc/" + s + ".yuv");
                p.waitFor();
                fgDetectionThread.join();

                String fgTiles = tiles.getFGTiles();
                String bgTiles = tiles.getBGTiles();

                String[] fgTileIndx = fgTiles.split(",");
                String[] bgTileIndx = bgTiles.split(",");
                int numFGTiles = fgTileIndx.length; // tile indices separated by commas
                int numBGTiles = bgTileIndx.length;
                String[] outString; // outputString = "pathToSendFGTiles_numShiftedTiles_FGQP_BGQP"
                int pathId = 0;

                // Cold start phase to get estimates first
                if (coldStartPhase) {
                    if ((this.scheduler.estimatesFastPath.isColdStartPhaseRTT() == false) & (this.scheduler.estimatesFastPath.isColdStartPhaseBW() == false)
                            & (this.scheduler.estimatesSlowPath.isColdStartPhaseRTT() == false & (this.scheduler.estimatesSlowPath.isColdStartPhaseBW() == false))) {
                        coldStartPhase = false;
                        System.out.println("Cold start phase ended");
                    }
                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + 24 + " " + bgTiles + " src/main/java/nrl/fg/" + s + ".mp4 " + 24 + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
                    pp.waitFor();
                } else {
                    long fgFileSize = new File("src/main/java/nrl/fg/out" + (i - 1) + ".mp4").length();
                    long bgFileSize = new File("src/main/java/nrl/bg/out" + (i - 1) + ".mp4").length();

                    // Getting schedule
                    // input format = "numTilesInFG_FGTilesFileSize_numTilesInBG_BGTilesFileSize"
                    // output format = "numFGTile_pathToSchedule_numBGTiles_pathToSchedule"
                    // 0 mean primary path and 1 means secondary path
                    outString = this.scheduler.getSchedule(numFGTiles + "_" + fgFileSize + "_" + numBGTiles + "_" + bgFileSize).split("_");
                    System.out.println("Schedule output: " + outString[0] + " " + outString[1] + " " + outString[2] + " " + outString[3]);
                    pathId = Integer.parseInt(outString[1]);
                    int numFGTilesToSend = Integer.parseInt(outString[0]);
                    int numBGTilesToSend = Integer.parseInt(outString[2]);

                    if ((scheduler.schedulerType == "musher") | (scheduler.schedulerType == "BFlow")) {
                        String headerStringFG = "";
                        for (int j = 1; j <= numFGTilesToSend - 1; j++) {
                            headerStringFG += String.format("%02d", j) + ",";
                        }

                        String headerStringBG = "";
                        for (int k = numFGTilesToSend; k <= (scheduler.totalTiles - 1); k++) {
                            headerStringBG += String.format("%02d", k) + ",";
                        }

                        fgTiles = headerStringFG;
                        bgTiles = headerStringBG;
                        numFGTiles = numFGTilesToSend - 1;
                        numBGTiles = numBGTilesToSend;
                    }

                    String[] fgBGQP = this.qps.get(i).toString().split(" ");
//                    System.out.println(fgBGQP[1] + " " + fgBGQP[2] + " " + fgBGQP[3]);
                    int fgQP = Integer.parseInt(fgBGQP[2]);
                    int bgQP = Integer.parseInt(fgBGQP[3]);
                    String currentQP = " [fgQP_bgQP_numFGTiles_numBGTiles] " + fgQP + " " + bgQP + " " + numFGTiles + " " + numBGTiles + "\n";
                    Files.write(Paths.get("src/main/java/nrl/current_QP_walk_bflow_Video5.txt"), currentQP.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + fgQP + " " + bgTiles + " src/main/java/nrl/fg/" + s + ".mp4 " + bgQP + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
                    pp.waitFor();
                    System.out.println("Using QP: " + fgQP + " " + bgQP);

//                    int qp = this.scheduler.getQpToUse(fgFileSize + bgFileSize);
//                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + qp + " " + bgTiles + " src/main/java/nrl/fg/" + s + ".mp4 " + qp + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
//                    pp.waitFor();
//                    System.out.println("Using QP: " + qp);
                }

//                Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + 24 + " " + bgTiles + " src/main/java/nrl/fg/" + s + ".mp4 " + 24 + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
//                pp.waitFor();
                this.receiverQueue.add(s + ".mp4_" + fgTiles + "_" + bgTiles + "_" + numFGTiles + "_" + numBGTiles + "_" + pathId);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        try {
            captureScreen();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}


class EvictingQueue {
    private int _size;
    private int drops = 0;
    private LinkedBlockingQueue<String> _queue;

    public EvictingQueue(int size) {
        this._size = size;
        this._queue = new LinkedBlockingQueue<>(this._size);
    }

    public void add(String element) {
        if (this._queue.size() < this._size) {
            try {
                this._queue.put(element);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            try {
                this._queue.take();
                this._queue.put(element);
                this.drops += 1;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public int getDrops() {
        return this.drops;
    }

    public String take() throws IOException, InterruptedException {
        return this._queue.take();
    }

    public int size() {
        return this._queue.size();
    }
}


class SendingClass implements Runnable {
    SctpChannel connectionChannelPrimary;
    SctpChannel connectionChannelSecondary;
    InetSocketAddress serverSocketAddressPrimary;
    InetSocketAddress serverSocketAddressSecondary;
    static String nrlCharacters;
    static String midCharacters;
    static String endCharacters;
    int sendBufferSize = 1000;
    String fgDir;
    String bgDir;
    EvictingQueue queue;
    Scheduler scheduler;

    public SendingClass(int primaryPort, int secondaryPort, EvictingQueue queue, String fgDirectory, String bgDirectory, Scheduler scheduler) {
        this.fgDir = fgDirectory;
        this.bgDir = bgDirectory;
        this.queue = queue;
        this.scheduler = scheduler;
        this.serverSocketAddressPrimary = new InetSocketAddress(primaryPort);
        this.serverSocketAddressSecondary = new InetSocketAddress(secondaryPort);
        this.nrlCharacters = "\nNRL\n";         // Identifier to segregate our timestamps
        this.midCharacters = "\nMID\n";         // To detect segments chunks that are no the last
        this.endCharacters = "\nEND\n";         // To detect last segment chunk
    }

    public void establishConnection(int connId) throws IOException {
        System.out.println("connection channel opened");
        SctpServerChannel sctpServerChannel = SctpServerChannel.open();
        if (connId == 0) {
            sctpServerChannel.bind(serverSocketAddressPrimary);
            connectionChannelPrimary = sctpServerChannel.accept();
            System.out.println("connection established for primary");
        } else {
            sctpServerChannel.bind(serverSocketAddressSecondary);
            connectionChannelSecondary = sctpServerChannel.accept();
            System.out.println("connection established for helper");
        }
    }

    public byte[] readFile(String filename) throws IOException {
        File file = new File(filename);
        if (file.exists()) {
            FileInputStream fl = new FileInputStream(file);
            byte[] arr = new byte[(int) file.length()];
            int res = fl.read(arr);
            if (res < 0) {
                System.out.println("Error in reading file");
                fl.close();
                return null;
            }
            fl.close();
            return arr;
        } else {
            return null;
        }
    }

    public void sendBytes(String filename, int connId, String headerString, int numOfTilesBeingSent) throws IOException {
        byte[] message = readFile(filename);
        System.out.println("Filename: " + filename + " " + new File(filename).exists());
        assert message != null;
        int cntIndex = sendBufferSize;
        int prevIndex = 0;
        boolean isBreak = false;
        long unixTime = System.currentTimeMillis();

        // Reading bytes from the read file and sending it on the SCTP socket
        while (!isBreak) {
            byte[] slice;
            ByteBuffer byteBuffer = null;
            if (prevIndex + sendBufferSize >= message.length) {
                slice = Arrays.copyOfRange(message, prevIndex, message.length);

                // Adding our custom header to the last chunk
                // Header format = \nNRL\nTimestamp_TileTypeIdentifier_CommaSeparatedTileIndices\nEND\n#ofTiles
                byte[] header = (nrlCharacters + unixTime + headerString + endCharacters + String.format("%02d", numOfTilesBeingSent)).getBytes();

                byteBuffer = ByteBuffer.allocate(slice.length + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
                byteBuffer.flip();
                isBreak = true;
            } else {
                slice = Arrays.copyOfRange(message, prevIndex, cntIndex);
                prevIndex = cntIndex;
                cntIndex = cntIndex + sendBufferSize;

                // Adding custom header to mid chunks
                // Header format = \nNRL\nTimestamp\nMID\n#ofTiles
                byte[] header = (nrlCharacters + unixTime + midCharacters + String.format("%02d", numOfTilesBeingSent)).getBytes();

                byteBuffer = ByteBuffer.allocate(sendBufferSize + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
                byteBuffer.flip();
            }
            try {
                final MessageInfo messageInfo = MessageInfo.createOutgoing(null, 0);
                if (connId == 0) {
                    connectionChannelPrimary.send(byteBuffer, messageInfo);
                } else {
                    connectionChannelSecondary.send(byteBuffer, messageInfo);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void Send() throws InterruptedException, IOException {
        while (true) {
            if (this.queue.size() == 0) {
                continue;
            }

            String[] string = this.queue.take().split("_"); // filename.mp4_fgTiles_bgTiles_numFGTiles_numBGTiles_pathIDForFGTiles; pathID = 0 for primary, 1 for secondary
//            System.out.println("From queue: " + string[0] + " " + string[1] + " " + string[2] + " " + string[3] + " " + string[4] + " " + string[5]);
            String tmpFile = string[0];
            String fgTiles = string[1].substring(0, string[1].length() - 1);
            String bgTiles = string[2].substring(0, string[2].length() - 1);
            Integer pathID = Integer.parseInt(string[5]);
            int numFGTiles = Integer.parseInt(string[3]);
            int numBGTiles = Integer.parseInt(string[4]);

            // Sending segments
            String headerStringFG = "_0_" + fgTiles + "_"; // 0 represent fg tiles; got from face detection model
            sendBytes(fgDir + tmpFile, pathID, headerStringFG, numFGTiles);

            String headerStringBG = "_1_" + bgTiles + "_"; // 1 represent bg tiled
            sendBytes(bgDir + tmpFile, 1 - pathID, headerStringBG, numBGTiles);
        }
    }

    @Override
    public void run() {
        try {
            Send();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}


public class Main {
    public static void main(String[] args) throws IOException, InterruptedException, AWTException {
        // Default parameters
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        int timestampSendingInterval = 400;
        int RTTTimeout = 800;
        int primaryPort = 6002;
        int secondaryPort = 6003;
        int rttChannelPortPrimary = 8001;
        int rttChannelPortSecondary = 8002;
        int controlChannelHandlerPortPrimary = 8003;
        int controlChannelHandlerPortHelper = 8004;
        int hmEstimateWindow = 5;
        float minConfidenceForForegroundDetection = 0.5F;
        String schedulerType = "BFlow"; // type = "none" or "musher" or "BFlow"; Content-aware schedulers: "minRTT" or "minCT"

        // Clearing old files
        try {
            Process p = Runtime.getRuntime().exec("bash src/main/java/nrl/clean.sh");
            p.waitFor();
            System.out.println("Files Cleaned");
        } catch (Exception e) {
            e.printStackTrace();
        }

        String backgroundDirectory = "src/main/java/nrl/bg/";
        String foregroundDirectory = "src/main/java/nrl/fg/";

        Estimates estimatesForPrimaryPath = new Estimates();
        Estimates estimatesForSecondaryPath = new Estimates();

        //////////////////////////// FG Detection ////////////////////////////////
        TileDims tileDims = new TileDims();
        TilesOfFGAndBG tiles = new TilesOfFGAndBG();
        ForegroundDetection fgDetector = new ForegroundDetection(tileDims, tiles, minConfidenceForForegroundDetection);

        //////////////////////////// Control Channels ////////////////////////////
//        System.out.println("Starting Control Channels");
        // For RTT
        RTTHandler rttHandlerPrimary = new RTTHandler(rttChannelPortPrimary, RTTTimeout, 0, timestampSendingInterval, hmEstimateWindow, estimatesForPrimaryPath);
        RTTHandler rttHandlerHelper = new RTTHandler(rttChannelPortSecondary, RTTTimeout, 1, timestampSendingInterval, hmEstimateWindow, estimatesForSecondaryPath);

        Thread rttPrimaryThread = new Thread(rttHandlerPrimary);
        Thread rttHelperThread = new Thread(rttHandlerHelper);

        rttPrimaryThread.start();
        rttHelperThread.start();

        // For bandwidth and chunk completion time
        ControlChannelHandler controlChannelHandlerPrimary = new ControlChannelHandler(controlChannelHandlerPortPrimary, 0, hmEstimateWindow, estimatesForPrimaryPath);
        ControlChannelHandler controlChannelHandlerHelper = new ControlChannelHandler(controlChannelHandlerPortHelper, 1, hmEstimateWindow, estimatesForSecondaryPath);

        Thread controlChannelPrimaryThread = new Thread(controlChannelHandlerPrimary);
        Thread controlChannelHelperThread = new Thread(controlChannelHandlerHelper);

        // Control channel threads
        controlChannelPrimaryThread.start();
        controlChannelHelperThread.start();


        //////////////////////////// Scheduler /////////////////////////////
        // type = "none" or "minRTT" or "minCT" or "musher" or "BFlow"
        Scheduler scheduler = new Scheduler(schedulerType, estimatesForPrimaryPath, estimatesForSecondaryPath);

        //////////////////////////// Videos Channels /////////////////////////////
        System.out.println("Starting Video Channels");
        EvictingQueue queue = new EvictingQueue(2);
        SendingClass sendingObj = new SendingClass(primaryPort, secondaryPort, queue, foregroundDirectory, backgroundDirectory, scheduler);

        sendingObj.establishConnection(1);
        System.out.println("Helper connected");
        sendingObj.establishConnection(0);
        System.out.println("Primary Connected");

        ScreenCapture sc = new ScreenCapture(queue, 10000, fgDetector, tiles, scheduler);

        Thread screenCaptureThread = new Thread(sc);
        Thread sendingThread = new Thread(sendingObj);

        screenCaptureThread.start();
        sendingThread.start();
        screenCaptureThread.join();
        sendingThread.join();

        controlChannelPrimaryThread.join();
        controlChannelHelperThread.join();

        rttPrimaryThread.join();
        rttHelperThread.join();
    }
}
