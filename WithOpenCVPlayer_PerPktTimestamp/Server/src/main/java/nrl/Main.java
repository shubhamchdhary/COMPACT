package nrl;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


class ScreenCapture implements Runnable {
    EvictingQueue primaryQueue;
    EvictingQueue helperQueue;
    int iterations;
    ForegroundDetection fgDetector;
    TilesOfFGAndBG tiles;
    Scheduler scheduler;
    private int stickiness = 2;             // number of times to repeat the same schedule (stickiness)
    private String[] lastSchedule = null;
    ArrayList qps;
    boolean isLive;

    public ScreenCapture(EvictingQueue primaryQueue, EvictingQueue helperQueue, int iterations, ForegroundDetection fgDetector, TilesOfFGAndBG tiles, Scheduler scheduler, boolean isLive) throws IOException {
        this.primaryQueue = primaryQueue;
        this.helperQueue = helperQueue;
        this.iterations = iterations;
        this.fgDetector = fgDetector;
        this.scheduler = scheduler;
        this.tiles = tiles;
        this.isLive = isLive;
        this.qps = this.readQPFile("src/main/java/nrl/QP_car_Video5.txt");
    }

    public ArrayList readQPFile(String filename) throws IOException {
        // reads QPs logged from our scheduler from file to replay
        ArrayList<String> lines = (ArrayList<String>) Files.readAllLines(new File(filename).toPath(), Charset.forName("utf-8"));
        return lines;
    }

    public void captureScreen() throws IOException {
        boolean coldStartPhase = true;
        for (int i = 0; i < iterations; i++) {
            String s = new String("out" + i);
//            long captureStartTime = System.currentTimeMillis();
            try {
                // Detecting foreground
                long fgScheduleTime = System.currentTimeMillis();
                Thread fgDetectionThread = new Thread(this.fgDetector);
                fgDetectionThread.start(); // returns immediately

                // For generating both fg and bg together
                Process p;
                long start = System.currentTimeMillis();
                if (this.isLive) {
                    p = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_live.sh 25 4 src/main/java/nrl/hevc/" + s + ".yuv");
                } else{
                    p = Runtime.getRuntime().exec("bash src/main/java/nrl/capture.sh 25 4 src/main/java/nrl/hevc/" + s + ".yuv");
                }
                p.waitFor();
                fgDetectionThread.join();
//                long rawCapTime = System.currentTimeMillis();
//                System.out.println("Raw capture time: " + (rawCapTime - captureStartTime));

                String fgTiles = tiles.getFGTiles();
                String bgTiles = tiles.getBGTiles();

                String fgQp = "24";
                String bgQP = "24";
                String[] fgTileIndx = fgTiles.split(",");
                String[] bgTileIndx = bgTiles.split(",");
                int numFGTiles = fgTileIndx.length; // tile indices separated by commas
                int numBGTiles = bgTileIndx.length;
                String[] outString; // outputString = "pathToSendFGTiles_numShiftedTiles_FGQP_BGQP"
                int numShiftedTiles;
                String newBGTiles = null;
                int pathId = 0;

                // Cold start phase to get estimates first
                if (coldStartPhase) {
                    if ((this.scheduler.estimatesFastPath.isColdStartPhaseRTT() == false) & (this.scheduler.estimatesFastPath.isColdStartPhaseBW() == false)
                            & (this.scheduler.estimatesSlowPath.isColdStartPhaseRTT() == false & (this.scheduler.estimatesSlowPath.isColdStartPhaseBW() == false))) {
                        coldStartPhase = false;
//                        System.out.println("Cold start phase ended");
                    }
                    newBGTiles = bgTiles;
                } else {
                    long fgFileSize = new File("src/main/java/nrl/fg/out" + (i - 1) + ".mp4").length();
                    long bgFileSize = new File("src/main/java/nrl/bg/out" + (i - 1) + ".mp4").length();
//                    System.out.println("FileSize Before Schedule: " + fgFileSize + " " + bgFileSize);

                    if (i % this.stickiness == 0 || this.lastSchedule == null) {
                        // Getting schedule
                        // input format = "numTilesInFG_FGTilesFileSize_numTilesInBG_BGTilesFileSize"
                        // output format = "pathToSendFGTiles_numShiftedTiles_FGQP_BGQP"
                        // 0 mean primary path and 1 means secondary path
                        long startScheduleTime = System.currentTimeMillis();
                        String scheduleOutput = this.scheduler.getSchedule(numFGTiles + "_" + fgFileSize + "_" + numBGTiles + "_" + bgFileSize);
                        long endScheduleTime = System.currentTimeMillis();
//                        System.out.println("[Scheduling Time] " + (endScheduleTime-startScheduleTime));
                        outString = scheduleOutput.split("_");
//                        System.out.println("FG BG Tiles: " + fgTiles + " " + bgTiles);
//                        System.out.println("Schedule: " + outString[0] + " " + outString[1] + " " + outString[2] + " " + outString[3] + " " + numFGTiles + " " + numBGTiles);
                        this.lastSchedule = (scheduleOutput + "_" + numFGTiles + "_" + numBGTiles + "_" + fgTiles + "_" + bgTiles).split("_");
                    } else {
                        outString = this.lastSchedule;
                        fgTiles = outString[6];
                        bgTiles = outString[7];
                        fgTileIndx = fgTiles.split(",");
                        bgTileIndx = bgTiles.split(",");
                        numFGTiles = Integer.parseInt(outString[4]);
                        numBGTiles = Integer.parseInt(outString[5]);
//                        System.out.println("Sticky Schedule: " + outString[0] + " " + outString[1] + " " + outString[2] + " " + outString[3] + " " + outString[4] + " " + outString[5] + " " + numFGTiles + " " + numBGTiles + " " + outString[6] + " " + outString[7]); // numFGTiles + " " + numBGTiles);
                    }

//                    System.out.println("Tile Indices: " + String.join(",", fgTileIndx) + " " + String.join(",", bgTileIndx));
//                    String scheduleLog = " [pathToSendFGTiles_numShiftedTiles_FGQP_BGQP] " + outString[0] + " " + outString[1] + " " + outString[2] + " " + outString[3] + "\n";
//                    Files.write(Paths.get("src/main/java/nrl/schedule_car.txt"), scheduleLog.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                    pathId = Integer.parseInt(outString[0]);
//                    fgQp = outString[2];
//                    bgQP = outString[3];
                    numShiftedTiles = Integer.parseInt(outString[1]);
                    if (numShiftedTiles > 0) {
                        for (int o = 0; o < numShiftedTiles; o++) {
                            fgTiles += bgTileIndx[o] + ","; // shifts tiles front of BG to FG tiles
                            newBGTiles = bgTiles.substring(3 * (o + 1), bgTiles.length());  // removes tiles added to BG tiles shifted to FG
                        }
                        numFGTiles += numShiftedTiles;
                        numBGTiles -= numShiftedTiles;
                    } else {
                        newBGTiles = bgTiles;
                    }
                }

                String[] fgBGQP = this.qps.get(i).toString().split(" ");
                fgQp = fgBGQP[2];
                bgQP = fgBGQP[3];
//                long schedEndTime = System.currentTimeMillis();
//                System.out.println("Scheduling time: " + (schedEndTime - rawCapTime));
                String currentQP = " [fgQP_bgQP_numFGTiles_numBGTiles_segIndex] " + fgQp + " " + bgQP + " " + numFGTiles + " " + numBGTiles + " " + i + " " + System.currentTimeMillis() + "\n";
                Files.write(Paths.get("src/main/java/nrl/QP_car_video2.txt"), currentQP.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Process pp;

                if (numBGTiles != 0) {
                    pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + fgQp + " " + newBGTiles + " src/main/java/nrl/fg/" + s + ".mp4 " + bgQP + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
                } else {
                    pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac_nobg.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + fgQp + " src/main/java/nrl/fg/" + s + ".mp4 " + bgQP + " " + fgTiles + " src/main/java/nrl/bg/" + s + ".mp4");
                }

                pp.waitFor();
                long end = System.currentTimeMillis();
//                System.out.println("[Encoding Time] " + (end - start));
//                System.out.println("[FG Detection Time] : " + (end - fgScheduleTime));
//                long captureEndTime = System.currentTimeMillis();
//                System.out.println(s + ".mp4_" + fgTiles + "_" + newBGTiles + "_" + pathId);
//                this.receiverQueue.add(s + ".mp4_" + fgTiles + "_" + bgTiles);
                String element = s + ".mp4_" + fgTiles + "_" + newBGTiles + "_" + numFGTiles + "_" + numBGTiles + "_" + pathId;
                this.primaryQueue.add(element);
                this.helperQueue.add(element);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        try {
            captureScreen();
        } catch (IOException e) {
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


class ConnectionManager {
    private SctpChannel connectionChannel;
    private InetSocketAddress serverSocketAddress;
    private int connId;
    private int port;

    public ConnectionManager(int port, int connId) {
        this.serverSocketAddress = new InetSocketAddress(port);
        this.connId = connId;
        this.port = port;
    }

    public SctpChannel establishConnection() throws IOException {
        SctpServerChannel sctpServerChannel = SctpServerChannel.open();
        sctpServerChannel.
                bind(serverSocketAddress);
        connectionChannel = sctpServerChannel.accept();
        if (this.connId == 0) {
            System.out.println("Connection established for Primary Path");
        } else {
            System.out.println("Connection established for Helper Path");
        }
        return connectionChannel;
    }

    public SctpChannel reEstablishConnection() throws IOException {
        this.serverSocketAddress = new InetSocketAddress(this.port);
        SctpServerChannel sctpServerChannel = SctpServerChannel.open();
//        sctpServerChannel.unbindAddress(serverSocketAddress.getAddress());
        sctpServerChannel.bind(serverSocketAddress);
//        System.out.println("Reconnecting: " + connId);
        connectionChannel = sctpServerChannel.accept();
        if (this.connId == 0) {
//            System.out.println("Connection re-established for Primary Path");
        } else {
//            System.out.println("Connection re-established for Helper Path");
        }
        return connectionChannel;
    }
}


class SendingClass implements Runnable {
    SctpChannel primaryChannel;
    SctpChannel secondaryChannel;
    static String nrlCharacters;
    static String midCharacters;
    static String endCharacters;
    int sendBufferSize = 1000; // 10000;
    String fileDir;
    EvictingQueue queue;
    int pathId;                 // 0 for FG and 1 for BG
    ArrayList<SctpChannel> channels;

    public SendingClass(SctpChannel primaryChannel, SctpChannel secondaryChannel, EvictingQueue queue, String fileDirectory, int pathID, ArrayList<SctpChannel> channels) {
        this.primaryChannel = primaryChannel;
        this.secondaryChannel = secondaryChannel;
        this.queue = queue;
        this.fileDir = fileDirectory;
        this.pathId = pathID;
        this.channels = channels;
        this.nrlCharacters = "\nNRL\n";         // Identifier to segregate our timestamps
        this.midCharacters = "\nMID\n";         // To detect segments chunks that are no the last
        this.endCharacters = "\nEND\n";         // To detect last segment chunk
    }

    public byte[] readFile(String filename) throws IOException {
        File file = new File(filename);
        if (file.exists()) {
            FileInputStream fl = new FileInputStream(file);
            byte[] arr = new byte[(int) file.length()];
            int res = fl.read(arr);
            if (res < 0) {
//                System.out.println("Error in reading file");
                fl.close();
                return null;
            }
            fl.close();
            return arr;
        } else {
            return null;
        }
    }

    public void sendBytes(String filePath, String filename, int connId, String headerString, int numOfTilesBeingSent) throws IOException {
        byte[] message = readFile(filePath + filename);
        int segmentIndex = Integer.parseInt(filename.split("\\.")[0].substring(3));
//        assert message != null;
        int cntIndex = sendBufferSize;
        int prevIndex = 0;
        boolean isBreak = false;
        long unixTime = System.currentTimeMillis();

        // Reading bytes from the read file and sending it on the SCTP socket
        while (!isBreak) {
            if (message == null){break;}
            byte[] slice;
            ByteBuffer byteBuffer = null;
            if (prevIndex + sendBufferSize >= message.length) {
                slice = Arrays.copyOfRange(message, prevIndex, message.length);

                // Adding our custom header to the last chunk
                // Header format = \nNRL\nTimestamp_TileTypeIdentifier_CommaSeparatedTileIndices_\nEND\nsegmentIndexnumberOfTiles; segmentIndex has 5 digits and numberOfTiles has 2 digits
//                unixTime = System.currentTimeMillis();
                byte[] header = (nrlCharacters + unixTime + headerString + endCharacters + String.format("%05d", segmentIndex) + String.format("%02d", numOfTilesBeingSent)).getBytes();

                byteBuffer = ByteBuffer.allocate(slice.length + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
//                System.out.println("Header attached" + new String(header));
                byteBuffer.flip();
                isBreak = true;
            } else {
                slice = Arrays.copyOfRange(message, prevIndex, cntIndex);
                prevIndex = cntIndex;
                cntIndex = cntIndex + sendBufferSize;

                // Adding custom header to mid chunks
                // Header format = \nNRL\nTimestamp\nMID\nSegmentNumber#ofTiles
//                unixTime = System.currentTimeMillis();
                byte[] header = (nrlCharacters + unixTime + midCharacters + String.format("%05d", segmentIndex) + String.format("%02d", numOfTilesBeingSent)).getBytes();

                byteBuffer = ByteBuffer.allocate(sendBufferSize + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
                byteBuffer.flip();
            }
            try {
                final MessageInfo messageInfo = MessageInfo.createOutgoing(null, 0);
//                    System.out.println("Sent buffer size: " + byteBuffer.limit() + " " + String.valueOf(unixTime));
                if (connId == 0)
                    if (primaryChannel.isOpen()) {
                        this.primaryChannel.send(byteBuffer, messageInfo);
                    } else {
//                        System.out.println("Primary channel is closed");
                    }
                else if (secondaryChannel.isOpen()) {
                    this.secondaryChannel.send(byteBuffer, messageInfo);
                } else {
//                    System.out.println("Secondary channel is closed");
                }
            } catch (Exception e) {
//                System.out.println("Status: " + primaryChannel.isOpen() + " " + secondaryChannel.isOpen());
                this.channels.get(connId).shutdown();
                this.channels.get(connId).close();
//                System.out.println("After Status: " + primaryChannel.isOpen() + " " + secondaryChannel.isOpen());
//                e.printStackTrace();
//                System.out.println(connId + " Socket closed");
            }
        }
    }

    public void Send() throws InterruptedException, IOException {
        while (true) {
            if (this.queue.size() == 0) {
                continue;
            }

            String[] string = this.queue.take().split("_");  // filename.mp4_fgTiles_bgTiles_numFGTiles_numBGTiles_pathIDForFGTiles; pathID = 0 for primary, 1 for secondary
//            System.out.println("From queue: " + string[0] + " " + string[1] + " " + string[2] + " " + string[3] + " " + string[4] + " " + string[5]);
            String bgTiles;
            int numBGTiles;
            String tmpFile = string[0];
            String fgTiles = string[1].substring(0, string[1].length() - 1); // removes comma from the last index (02,04,05,06,)

            if (string[2] == "") {                                           // checks whether all BG tiles are shifted to FG
                bgTiles = "00";
                numBGTiles = 1;
            } else {
                bgTiles = string[2].substring(0, string[2].length() - 1);
                numBGTiles = Integer.parseInt(string[4]);
            }

            Integer pathID = Integer.parseInt(string[5]);
            int numFGTiles = Integer.parseInt(string[3]);

            // Sending Segments
//            long t1_1 = 0, t1_2 = 0, t2_1 = 0, t2_2 = 0;
            if (this.pathId == 0) {
//                t1_1 = System.currentTimeMillis();
                String headerStringFG = "_0_" + fgTiles + "_"; // 0 represent fg tiles; got from face detection model
                sendBytes(this.fileDir, tmpFile, pathID, headerStringFG, numFGTiles);
//                t2_1 = System.currentTimeMillis();
            } else {
//                t1_2 = System.currentTimeMillis();
                String headerStringBG = "_1_" + bgTiles + "_"; // 1 represent bg tiles
                sendBytes(this.fileDir, tmpFile, 1 - pathID, headerStringBG, numBGTiles);
//                t2_2 = System.currentTimeMillis();

//            System.out.println("Headers: " + headerStringFG + " " + headerStringBG);
            }

//            System.out.println("FG time: " + (t2_1 - t1_1) + " BG time: " + (t2_2 - t1_2));
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
    public static void main(String[] args) throws IOException {
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
        boolean liveExperiment = false;

        // Clearing old files
        try {
            Process p = Runtime.getRuntime().exec("bash src/main/java/nrl/clean.sh");
            p.waitFor();
//            System.out.println("Files Cleaned");
        } catch (Exception e) {
            e.printStackTrace();
        }

        String backgroundDirectory = "src/main/java/nrl/bg/";
        String foregroundDirectory = "src/main/java/nrl/fg/";

        Estimates estimatesForPrimaryPath = new Estimates();
        Estimates estimatesForSecondaryPath = new Estimates();
        ExecutorService pool = Executors.newFixedThreadPool(8); // Creates a thread pool to manage all threads

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

//        rttPrimaryThread.start();
//        rttHelperThread.start();
        pool.execute(rttPrimaryThread);
        pool.execute(rttHelperThread);

        // For bandwidth and chunk completion time
        ControlChannelHandler controlChannelHandlerPrimary = new ControlChannelHandler(controlChannelHandlerPortPrimary, 0, hmEstimateWindow, estimatesForPrimaryPath);
        ControlChannelHandler controlChannelHandlerHelper = new ControlChannelHandler(controlChannelHandlerPortHelper, 1, hmEstimateWindow, estimatesForSecondaryPath);

        Thread controlChannelPrimaryThread = new Thread(controlChannelHandlerPrimary);
        Thread controlChannelHelperThread = new Thread(controlChannelHandlerHelper);

        // Control channel threads
        controlChannelPrimaryThread.start();
        controlChannelHelperThread.start();
        pool.execute(controlChannelPrimaryThread);
        pool.execute(controlChannelHelperThread);

        //////////////////////////// Scheduler /////////////////////////////
        Scheduler scheduler = new Scheduler(estimatesForPrimaryPath, estimatesForSecondaryPath);

        //////////////////////////// Videos Channels /////////////////////////////
//        System.out.println("Starting Video Channels");
        EvictingQueue primaryQueue = new EvictingQueue(2);
        EvictingQueue helperQueue = new EvictingQueue(2);

        // NOTE: Connects with helper first. So start primary after helper.
        ConnectionManager helperConnectionManager = new ConnectionManager(secondaryPort, 1);
        SctpChannel helperConnection = helperConnectionManager.establishConnection();
        ConnectionManager primaryConnectionManager = new ConnectionManager(primaryPort, 0);
        SctpChannel primaryConnection = primaryConnectionManager.establishConnection();

        ArrayList<SctpChannel> channels = new ArrayList<>(Arrays.asList(primaryConnection, helperConnection));

        SendingClass FGSender = new SendingClass(primaryConnection, helperConnection, primaryQueue, foregroundDirectory, 0, channels);
        SendingClass BGSender = new SendingClass(primaryConnection, helperConnection, helperQueue, backgroundDirectory, 1, channels);

        ScreenCapture sc = new ScreenCapture(primaryQueue, helperQueue, 10000, fgDetector, tiles, scheduler, liveExperiment);

        Thread screenCaptureThread = new Thread(sc);
        Thread FGSenderThread = new Thread(FGSender);
        Thread BGSenderThread = new Thread(BGSender);

//        screenCaptureThread.start();
//        FGSenderThread.start();
//        BGSenderThread.start();
        pool.execute(screenCaptureThread);
        pool.execute(FGSenderThread);
        pool.execute(BGSenderThread);

        while (true) {
            for (int conn = 0; conn < 2; conn++) {
//                System.out.println(conn + " " + channels.get(conn).isOpen());
                if (channels.get(conn).isOpen() == false) {
//                    System.out.println("Re-connecting");
                    if (conn == 0 && estimatesForPrimaryPath.getChannelStatus() == false) {    // reconnects with primary
                        estimatesForPrimaryPath.setChannelStatus(true);      // primary channel down
                        System.out.println("Primary status made down");
//                        channels.set(conn, primaryConnectionManager.reEstablishConnection());
//                        System.out.println("Re-established connection with primary");
//
//                        FGSender = new SendingClass(channels.get(conn), helperConnection, primaryQueue, foregroundDirectory, 0, channels);
//                        pool.execute(new Thread(FGSender));
                    } else if (conn == 1 && estimatesForSecondaryPath.getChannelStatus() == false) {              // reconnects with secondary
                        estimatesForSecondaryPath.setChannelStatus(true);  // secondary channel down
                        System.out.println("Secondary status made down");
//                        channels.set(conn, helperConnectionManager.reEstablishConnection());
//                        System.out.println("Re-established connection with helper");
//
//                        BGSender = new SendingClass(channels.get(conn), helperConnection, primaryQueue, foregroundDirectory, 0, channels);
//                        pool.execute(new Thread(BGSender));
                    }
                }
            }
        }

//        screenCaptureThread.join();
//        FGSenderThread.join();
//        BGSenderThread.join();
//
//        controlChannelPrimaryThread.join();
//        controlChannelHelperThread.join();
//
//        rttPrimaryThread.join();
//        rttHelperThread.join();
    }
}
