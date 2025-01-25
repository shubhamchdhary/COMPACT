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

    public ScreenCapture(EvictingQueue queue, int iterations, ForegroundDetection fgDetector, TilesOfFGAndBG tiles, Scheduler scheduler) throws IOException {
        this.receiverQueue = queue;
        this.iterations = iterations;
        this.fgDetector = fgDetector;
        this.scheduler = scheduler;
        this.tiles = tiles;
        this.qps = this.readQPFile("src/main/java/nrl/QP_walk_Video5.txt");
    }

    public ArrayList readQPFile(String filename) throws IOException {
        // reads QPs logged from our scheduler from file to replay
        ArrayList<String> lines = (ArrayList<String>) Files.readAllLines(new File(filename).toPath(), Charset.forName("utf-8"));
        return lines;
    }

    public void createQPMatrix(String filename, int fgQP, int bgQP, String[] bgTileIndx) throws IOException {
        String qpLine = "4 4\n";
//        System.out.println("bGTileIndex " + java.util.Arrays.toString(bgTileIndx));
        for (int h = 1; h <= 16; h++) {
            if (Arrays.stream(bgTileIndx).anyMatch(String.format("%02d", h)::equals)) {
//                    System.out.println("BG Tile "+g);
                qpLine += (bgQP - 22) + " ";
            } else {
                qpLine += (fgQP - 22) + " ";
            }
            if (h % 4 == 0) {
                qpLine += "\n";
            }
        }

        System.out.println(qpLine);
        Files.write(Paths.get(filename), qpLine.getBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);


    }


    public void captureScreen() throws IOException {
        boolean coldStartPhase = true;
        for (int i = 0; i < iterations; i++) {
            String s = new String("out" + i);
//            long captureStartTime = System.currentTimeMillis();
            try {
                Thread fgDetectionThread = new Thread(this.fgDetector);
                fgDetectionThread.start();
                // For generating fg and bg together
                Process p;
                p = Runtime.getRuntime().exec("bash src/main/java/nrl/capture.sh 25 4 src/main/java/nrl/hevc/" + s + ".yuv");
                p.waitFor();
                fgDetectionThread.join();

                String fgTiles = tiles.getFGTiles();
                String bgTiles = tiles.getBGTiles();

                String[] fgTileIndx = fgTiles.split(",");
                String[] bgTileIndx = bgTiles.split(",");
                int numFGTiles = fgTileIndx.length;
                int numBGTiles = bgTileIndx.length;
//                String[] outString;
//                int pathId = 0;
                if (coldStartPhase) {
                    // Streams at 24 QP (highest quality)
                    if ((this.scheduler.estimatesFastPath.isColdStartPhaseRTT() == false) & (this.scheduler.estimatesFastPath.isColdStartPhaseBW() == false)
                            & (this.scheduler.estimatesSlowPath.isColdStartPhaseRTT() == false & (this.scheduler.estimatesSlowPath.isColdStartPhaseBW() == false))) {
                        coldStartPhase = false;
                        System.out.println("Cold Start Phase ended");
                    }
                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac_bck.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + 24 + "  src/main/java/nrl/mp4/" + s + ".mp4");
                    pp.waitFor();
                } else {
//                    long fgFileSize = new File("src/main/java/nrl/fg/out" + (i - 1) + ".mp4").length();
//                    long bgFileSize = new File("src/main/java/nrl/bg/out" + (i - 1) + ".mp4").length();
                    String[] fgBgQP = this.qps.get(i).toString().split(" ");
                    int fgQP = Integer.parseInt(fgBgQP[2]);
                    int bgQP = Integer.parseInt(fgBgQP[3]);
                    this.createQPMatrix("src/main/java/nrl/QPFile.txt", fgQP, bgQP, bgTileIndx);
//                    System.out.println("Cold Start Phase Ended\n Using QP: " + qpToUse);

                    // Generating segments
//                    String[] fgBGQP = this.qps.get(i).toString().split(" ");
////                    System.out.println(fgBGQP[1] + " " + fgBGQP[2] + " " + fgBGQP[3]);
//                    int fgQP = Integer.parseInt(fgBGQP[2]);
//                    int bgQP = Integer.parseInt(fgBGQP[3]);
                    String currentQP = " [fgQP_bgQP_numFGTiles_numBGTiles] " + fgQP + " " + bgQP + " " + numFGTiles + " " + numBGTiles + "\n";
                    Files.write(Paths.get("src/main/java/nrl/currentQP_packetlevel_bflow_video5_walk.txt"), currentQP.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + "src/main/java/nrl/QPFile.txt" + " src/main/java/nrl/mp4/" + s + ".mp4");
                    pp.waitFor();
                    new File("src/main/java/nrl/QPFile.txt").delete();
                    System.out.println("Using QP: " + fgQP + " " + bgQP);

//                    Process pp = Runtime.getRuntime().exec("bash src/main/java/nrl/capture_only_gpac.sh " + "src/main/java/nrl/hevc/" + s + ".yuv 25 4x4 " + qpToUse +" src/main/java/nrl/mp4/" + s + ".mp4");
//                    pp.waitFor();
                }
                this.receiverQueue.add(s + ".mp4");
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


class SendingClass implements Runnable {
    SctpChannel connectionChannelPrimary;
    SctpChannel connectionChannelSecondary;
    InetSocketAddress serverSocketAddressPrimary;
    InetSocketAddress serverSocketAddressSecondary;
    static String nrlCharacters;
    static String midCharacters;
    static String endCharacters;
    int sendBufferSize;
    private int cntIndex = sendBufferSize;
    private int cntPackets = 0;
    String segmentDir;
    EvictingQueue queue;
    Scheduler scheduler;
    private int numPacketScheduledOverOneInterfaces;

    public SendingClass(int primaryPort, int secondaryPort, EvictingQueue queue, String directory, Scheduler scheduler, int numPacketScheduledOverOneInterfaces) {
        this.segmentDir = directory;
        this.queue = queue;
        this.scheduler = scheduler;
        this.serverSocketAddressPrimary = new InetSocketAddress(primaryPort);
        this.serverSocketAddressSecondary = new InetSocketAddress(secondaryPort);
        this.numPacketScheduledOverOneInterfaces = numPacketScheduledOverOneInterfaces;
        this.sendBufferSize = scheduler.packetSize;
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

    public void sendBytes(String filePath, String filename, int connId, int startPacket, int endPacket, int totalPackets) throws IOException {
        byte[] message = readFile(filePath + filename);
        System.out.println("Filename: " + filename + " " + new File(filePath + filename).exists());
        assert message != null;
        int prevIndex = startPacket * sendBufferSize;
        boolean isBreak = false;

        long unixTime = System.currentTimeMillis();
        String segmentNumber = String.format("%05d", Integer.parseInt(filename.split("\\.")[0].substring(3)));
        // Reading chunks from the read file and sending it on the SCTP socket
        while (!isBreak) {
            byte[] slice;
            ByteBuffer byteBuffer;
            String headerString = segmentNumber + String.format("%04d", (cntPackets)) + String.format("%03d", (totalPackets + 1));
//            System.out.println("Segment Number : " + segmentNumber);
//            System.out.println("starting buffer - " + prevIndex);
//            System.out.println("ending bufer - " + cntIndex);

            if (this.cntPackets == totalPackets) {
//                System.out.println("Last Packet of the segment");
//                System.out.println("Indices: " + connId + " " + prevIndex + " " + cntIndex + " " + cntPackets);
                slice = Arrays.copyOfRange(message, prevIndex, message.length);

                // Adding our custom header to the last chunk
                // Header format = \nNRL\nTimestamp5digitSegNumber4digitPktNumber3digittotalPackets\nEND\n
                byte[] header = (nrlCharacters + unixTime + headerString + endCharacters).getBytes();

                byteBuffer = ByteBuffer.allocate(slice.length + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
//                System.out.println("Header attached: " + connId + " " + new String(header) + " " + this.cntPackets);
                byteBuffer.flip();
                this.cntIndex = this.sendBufferSize;
                this.cntPackets = 0;
                isBreak = true;
            } else {
//                if (this.cntPackets == totalPackets) {
//                System.out.println("Indices: " + connId + " " + prevIndex + " " + cntIndex + " " + cntPackets);
                slice = Arrays.copyOfRange(message, prevIndex, cntIndex);
                prevIndex = cntIndex;
                cntIndex = cntIndex + sendBufferSize;

                // Adding custom header to mid chunks
                // Header format = \nNRL\nTimestamp\nMID\n
                byte[] header = (nrlCharacters + unixTime + headerString + midCharacters).getBytes();
//                System.out.println("Header attached: "  + connId + " " + new String(header) + " " + this.cntPackets);

                byteBuffer = ByteBuffer.allocate(sendBufferSize + header.length);
                byteBuffer.put(slice);
                byteBuffer.put(header);
                byteBuffer.flip();
                this.cntPackets++;
                if (this.cntPackets == endPacket) {
                    isBreak = true;
                }
//                }
            }
            try {
                final MessageInfo messageInfo = MessageInfo.createOutgoing(null, 0);
//                    System.out.println("Sent buffer size: " + byteBuffer.limit() + " " + String.valueOf(unixTime));
                if (connId == 0)
                    connectionChannelPrimary.send(byteBuffer, messageInfo);
                else
                    connectionChannelSecondary.send(byteBuffer, messageInfo);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void Send() throws InterruptedException, IOException {
        boolean coldStartPhase = true;
        int countPrimaryInterface = 0;
        int countSecondaryInterface = 0;
        while (true) {
            if (this.queue.size() == 0) {
                continue;
            }

            String segment = this.queue.take(); // filename.mp4 to send
            long fileSize = new File("src/main/java/nrl/mp4/" + segment).length();
//            System.out.println("Total file size: " + fileSize);

            int numberOfPackets = (int) Math.ceil((float) fileSize / this.sendBufferSize);
//            System.out.println("Number of Packets: "+ numberOfPackets);
            String outString;


            // Cold start phase to get estimates first
            if (coldStartPhase) {
                // Add code to send packets equally on both interfaces initially to get estimate
                if ((this.scheduler.estimatesFastPath.isColdStartPhaseRTT() == false) & (this.scheduler.estimatesFastPath.isColdStartPhaseBW() == false)
                        & (this.scheduler.estimatesSlowPath.isColdStartPhaseRTT() == false & (this.scheduler.estimatesSlowPath.isColdStartPhaseBW() == false))) {
                    coldStartPhase = false;
                    System.out.println("Cold start phase ended");
                } else {
                    // During cold start send equal packets on both paths
                    int numOfPacketsPrimary = (int) Math.ceil(0.5 * numberOfPackets);
//                    System.out.println("Num Pkts: " + numberOfPackets + " " + numOfPacketsPrimary + " " + (numberOfPackets - numOfPacketsPrimary));
                    sendBytes(this.segmentDir, segment, 0, 0, numOfPacketsPrimary, numberOfPackets - 1);
                    sendBytes(this.segmentDir, segment, 1, numOfPacketsPrimary, numberOfPackets, numberOfPackets - 1);
                }
            } else {
                // Sending Segments
                if (this.scheduler.schedulerType == "minRTT") {
                    outString = this.scheduler.getSchedule(numberOfPackets);
                    String[] parts = outString.split("_");
                    int pathID = Integer.parseInt(parts[1]);
//                    System.out.println("Num Pkts: " + numberOfPackets);
                    System.out.println("Schedule " + pathID + " " + numberOfPackets);
                    sendBytes(this.segmentDir, segment, pathID, 0, numberOfPackets, numberOfPackets - 1);
                } else if (this.scheduler.schedulerType == "musher") {
                    outString = this.scheduler.getSchedule(numberOfPackets);
                    String[] parts = outString.split("_");
                    int numOfPacketsPrimary = Integer.parseInt((parts[0]));
                    int pathIDPrimary = Integer.parseInt(parts[1]);
                    int pathIDSecondary = Integer.parseInt(parts[3]);
//                    System.out.println("Num Pkts: " + numberOfPackets + " " + numOfPacketsPrimary + " " + (numberOfPackets - numOfPacketsPrimary));
                    System.out.println("Schedule " + numOfPacketsPrimary + " " + pathIDPrimary + " " + (numberOfPackets - numOfPacketsPrimary) + " " + pathIDSecondary);
                    if (numOfPacketsPrimary == numberOfPackets && countPrimaryInterface < this.numPacketScheduledOverOneInterfaces) {
                        countPrimaryInterface++;
                        sendBytes(this.segmentDir, segment, pathIDPrimary, 0, numOfPacketsPrimary, numberOfPackets - 1);

                    } else if ((numberOfPackets - numOfPacketsPrimary) == numberOfPackets && countSecondaryInterface < this.numPacketScheduledOverOneInterfaces) {
                        countSecondaryInterface++;
                        sendBytes(this.segmentDir, segment, pathIDSecondary, numOfPacketsPrimary, numberOfPackets, numberOfPackets - 1);

                    } else if (countPrimaryInterface >= this.numPacketScheduledOverOneInterfaces || countSecondaryInterface >= this.numPacketScheduledOverOneInterfaces) {
                        // Forces some packet to other path if multiple schedules are with one path only
                        numOfPacketsPrimary = (int) Math.ceil(numberOfPackets / 2);
//                        System.out.println("Optimism Schedule :"+pathIDPrimary+" "+numOfPacketsPrimary+" "+pathIDSecondary+" "+(numberOfPackets-numOfPacketsPrimary));
                        sendBytes(this.segmentDir, segment, pathIDPrimary, 0, numOfPacketsPrimary, numberOfPackets - 1);
                        sendBytes(this.segmentDir, segment, pathIDSecondary, numOfPacketsPrimary, numberOfPackets, numberOfPackets - 1);
                        countPrimaryInterface = 0;
                        countSecondaryInterface = 0;
//                        System.out.println("Optimism");
                    } else {

                        sendBytes(this.segmentDir, segment, pathIDPrimary, 0, numOfPacketsPrimary, numberOfPackets - 1);
                        sendBytes(this.segmentDir, segment, pathIDSecondary, numOfPacketsPrimary, numberOfPackets, numberOfPackets - 1);
                    }
                } else if (this.scheduler.schedulerType == "BFlow") {
                    outString = this.scheduler.getSchedule(numberOfPackets);
                    String[] parts = outString.split("_");
                    int numberOfPacketsPrimary = Integer.parseInt(parts[0]);
                    int pathIDPrimary = Integer.parseInt(parts[1]);
                    int pathIDSecondary = Integer.parseInt(parts[3]);
                    System.out.println("Schedule :" + pathIDPrimary + " " + numberOfPacketsPrimary + " " + pathIDSecondary + " " + (numberOfPackets - numberOfPacketsPrimary));
                    sendBytes(this.segmentDir, segment, pathIDPrimary, 0, numberOfPacketsPrimary, numberOfPackets - 1);
                    sendBytes(this.segmentDir, segment, pathIDSecondary, numberOfPacketsPrimary, numberOfPackets, numberOfPackets - 1);
                } else if(this.scheduler.schedulerType == "SP1"){
                    outString = this.scheduler.getSchedule(numberOfPackets);
                    String[] parts = outString.split("_");

                    int pathIDPrimary = Integer.parseInt(parts[1]);
                    int pathIDSecondary = Integer.parseInt(parts[3]);
                    sendBytes(this.segmentDir,segment,pathIDPrimary,0,numberOfPackets,numberOfPackets-1);
                }else if(this.scheduler.schedulerType == "SP2"){
                    outString = this.scheduler.getSchedule(numberOfPackets);
                    String[] parts = outString.split("_");

//                    int pathIDPrimary = Integer.parseInt(parts[1]);
                    int pathIDSecondary = Integer.parseInt(parts[3]);
                    sendBytes(this.segmentDir,segment,pathIDSecondary,0,numberOfPackets,numberOfPackets-1);
                }
                System.out.println("# of time packet scheduled on one path: " + countPrimaryInterface + " " + countSecondaryInterface);
//            System.out.println("Headers: " + headerStringFG + " " + headerStringBG);
            }
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
        int numProbeVideoPackets = 4;
        int packetSize = 1000;

        float minConfidenceForForegroundDetection = 0.5F;
        String schedulerType = "BFlow"; // type = "minRTT" or "musher"

        // Clearing old files
        try {
            Process p = Runtime.getRuntime().exec("bash src/main/java/nrl/clean.sh");
            p.waitFor();
            System.out.println("Files Cleaned");
        } catch (Exception e) {
            e.printStackTrace();
        }

        String segmentDirectory = "src/main/java/nrl/mp4/";

        Estimates estimatesForPrimaryPath = new Estimates();
        Estimates estimatesForSecondaryPath = new Estimates();
        ///////////////////////// Foreground Detection /////////////////////////////
        TileDims tileDims = new TileDims();
        TilesOfFGAndBG tiles = new TilesOfFGAndBG();
        ForegroundDetection fgDetector = new ForegroundDetection(tileDims, tiles, minConfidenceForForegroundDetection);

        //////////////////////////// Control Channels ////////////////////////////
        // For RTT
        RTTHandler rttHandlerPrimary = new RTTHandler(rttChannelPortPrimary, RTTTimeout, 0, timestampSendingInterval, hmEstimateWindow, estimatesForPrimaryPath);
        RTTHandler rttHandlerHelper = new RTTHandler(rttChannelPortSecondary, RTTTimeout, 1, timestampSendingInterval, hmEstimateWindow, estimatesForSecondaryPath);

        Thread rttPrimaryThread = new Thread(rttHandlerPrimary);
        Thread rttHelperThread = new Thread(rttHandlerHelper);
////
        rttPrimaryThread.start();
        rttHelperThread.start();
//
//        // For bandwidth and chunk completion time
        ControlChannelHandler controlChannelHandlerPrimary = new ControlChannelHandler(controlChannelHandlerPortPrimary, 0, hmEstimateWindow, estimatesForPrimaryPath);
        ControlChannelHandler controlChannelHandlerHelper = new ControlChannelHandler(controlChannelHandlerPortHelper, 1, hmEstimateWindow, estimatesForSecondaryPath);

        Thread controlChannelPrimaryThread = new Thread(controlChannelHandlerPrimary);
        Thread controlChannelHelperThread = new Thread(controlChannelHandlerHelper);
////
//////         Control channel threads
        controlChannelPrimaryThread.start();
        controlChannelHelperThread.start();

        //////////////////////////// Scheduler /////////////////////////////
        Scheduler scheduler = new Scheduler(schedulerType, estimatesForPrimaryPath, estimatesForSecondaryPath, packetSize);
//        int qpUsed = scheduler.lastUsedQP;
//        String qpUsedToLog = Integer.toString(qpUsed);
//        String qpLog = "src/main/java/nrl/Packetlevel_QP_minRTT.txt";
//        Files.write(Paths.get(qpLog), qpUsedToLog.getBytes(), StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        //////////////////////////// Videos Channels /////////////////////////////
        System.out.println("Starting Video Channels");
        EvictingQueue queue = new EvictingQueue(2);
        SendingClass sendingObj = new SendingClass(primaryPort, secondaryPort, queue, segmentDirectory, scheduler, numProbeVideoPackets);

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
