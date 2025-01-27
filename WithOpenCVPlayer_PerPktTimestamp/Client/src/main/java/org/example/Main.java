package org.example;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.highgui.HighGui;


class EvictingQueue {
    private int _size;
    private int drops = 0;
    private LinkedBlockingQueue<String> _queue;
    private int pollWaitTime;

    public EvictingQueue(int size, int pollWaitTimeInMillis) {
        this._size = size;
        this.pollWaitTime = pollWaitTimeInMillis;
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

    public int getDrops(){
        return this.drops;
    }

    public String take() throws InterruptedException {
        return this._queue.poll(this.pollWaitTime, TimeUnit.MILLISECONDS);
    }

    public int size(){
        return this._queue.size();
    }
}


class Stats {
    int delayInFGAndBGSegments = 0;
}


class Receiver implements Runnable {
    InetSocketAddress socketAddress;
    SctpChannel receiverChannel;
    String primaryFileSavingDirectory;
    String helperFileSavingDirectory;
    int receiverId;
    Stats stats;

    private byte[] nrlBytes;
    private byte[] endHeaderBytes;
    private byte[] midHeaderBytes;

//    public HashMap<Integer, TimeStampEval> timeMap;
    private int receiveBufferSize = 2500;
    EvictingQueue primaryReceiverQueue;
    EvictingQueue helperReceiverQueue;
    ControlChannelHandler controlChannelHandler;
    private String networkLogFileName;

    public Receiver(String serverIp, int serverPort, String p_fileDirPath, String h_fileDirPath, int p_receiverId, EvictingQueue p_queue, EvictingQueue h_queue, ControlChannelHandler controlChannelHandler, String networkLogFileName, Stats stats) throws IOException {
        socketAddress = new InetSocketAddress(serverIp, serverPort);
        receiverChannel = SctpChannel.open(socketAddress, 1, 1);

        String reverseLastCharacters = "\nNRL\n";   // Identifier to segragate our timestamps
        String midCharacters = "\nMID\n";           // To detect segments chunks that are no the last
        String endCharacters = "\nEND\n";           // To detect last segment chunk
        nrlBytes = reverseLastCharacters.getBytes();
        midHeaderBytes = midCharacters.getBytes();
        endHeaderBytes = endCharacters.getBytes();

        this.primaryFileSavingDirectory = p_fileDirPath;
        this.helperFileSavingDirectory = h_fileDirPath;
        this.networkLogFileName = networkLogFileName;
        this.receiverId = p_receiverId;
        this.primaryReceiverQueue = p_queue;
        this.helperReceiverQueue = h_queue;
        this.controlChannelHandler = controlChannelHandler;
        this.stats = stats;
    }

    void receiveFile() throws IOException {
        ByteBuffer rxBuffer;
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        long videoSegmentSize = 0;

        while (true) {
//            System.out.println("Status: " + receiverId + " " + this.receiverChannel.isOpen());
            if (this.receiverChannel.isOpen()) {
                MessageInfo messageInfo = receiverChannel.receive(rxBuffer = ByteBuffer.allocateDirect(receiveBufferSize), null, null);
                int len = messageInfo.bytes();
                if (len == -1) {
                    break;
                }
//            System.out.println("[Receiver: " + this.receiverId + "] Total bytes received " + len);
                rxBuffer.flip();
                byte[] data = new byte[len];
                rxBuffer.get(data);
                rxBuffer.clear();
                videoSegmentSize += len;        // used to get total video segment size in bytes

                byte[] headerSlice = Arrays.copyOfRange(data, data.length - (endHeaderBytes.length + 5 + 2), (data.length - 5 - 2)); // +-2 for numberOfTiles added to header string, and +-5 for segmentIndex
//            System.out.println(this.receiverId + " Header Slice: " + new String(headerSlice));
                boolean equal = Arrays.equals(headerSlice, endHeaderBytes);
                if (equal) {
                    // If the chunk is the last one then save the segment
                    String receivingTime = String.valueOf(System.currentTimeMillis());
                    int timestampLength = receivingTime.getBytes().length;
                    int numTiles = Integer.parseInt(new String(Arrays.copyOfRange(data, data.length - 2, data.length)));
                    int segmentIndex = Integer.parseInt(new String(Arrays.copyOfRange(data, data.length - 5 - 2, data.length - 2)));
//                    System.out.println("Num Tiles: " + numTiles + " Segment Index: " + this.receiverId + " " + segmentIndex);
                    int headerInfoStringSize = 4 + (numTiles * 2) + (numTiles - 1); // size of the comma separated indices (_0_02,04,05,06_)

                    // Header Format: \nNRL\nTimestamp_TileTypeIdentifier_CommaSeparatedTileIndices_\nEND\nsegmentIndexnumberOfTiles; TileTypeIdentifier = 0 for FG and 1 for BG, Tiles = comma separated tile indices (02,04,15,16)
                    int headerLength = nrlBytes.length + timestampLength + headerInfoStringSize + endHeaderBytes.length + 5 + 2;
                    String headerInfoString = new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length + timestampLength, data.length - (endHeaderBytes.length + 5 + 2 + 1))); // +1 to remove last "_"
                    byte[] sendingTimestamp = Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length, data.length - (endHeaderBytes.length + 5 + 2 + headerInfoStringSize));
                    byte[] videoBytes = Arrays.copyOfRange(data, 0, data.length - headerLength);
                    String sendingTime = new String(sendingTimestamp);
//                  System.out.println("End Header String: " + headerInfoString + " Timestamp: " + sendingTime);

                    // Logging network lag in a file
//                    String s = "Network-Lag: " + this.receiverId + " " + (Long.parseLong(receivingTime) - Long.parseLong(sendingTime)) + "\n";
//                    System.out.print(s);
//                    Files.write(Paths.get(this.networkLogFileName), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                    fileBytes.write(videoBytes);

                    // Sending back the stats
//                    String stats = len + "_" + sendingTime + "_" + segmentIndex; // this.stats.delayInFGAndBGSegments; // receivedChunkSize_timestamp_segmentIndex
                    String stats = videoSegmentSize + "_" + sendingTime + "_" + segmentIndex; // this.stats.delayInFGAndBGSegments; // receivedChunkSize_timestamp_segmentIndex
                    this.controlChannelHandler.sendStats(stats);
//                    System.out.println("Segment: " + segmentIndex + " Size: " + videoSegmentSize);
                    videoSegmentSize = 0;


                    try {
                        String[] headerStrings = headerInfoString.split("_");
                        if (Integer.parseInt(headerStrings[1]) == 0) {
                            fileBytes.writeTo(new FileOutputStream(primaryFileSavingDirectory + "/file" + segmentIndex + ".mp4"));
                            this.primaryReceiverQueue.add(primaryFileSavingDirectory + "/file" + segmentIndex + ".mp4_" + headerStrings[2] + "_" + segmentIndex);
                        } else {
                            fileBytes.writeTo(new FileOutputStream(helperFileSavingDirectory + "/file" + segmentIndex + ".mp4"));
                            this.helperReceiverQueue.add(helperFileSavingDirectory + "/file" + segmentIndex + ".mp4_" + headerStrings[2] + "_" + segmentIndex);
                        }
//                    System.out.println("Queue Size: " + receiverQueue.size());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    fileBytes.reset();
                } else {
                    boolean _equal = Arrays.equals(headerSlice, midHeaderBytes);
                    if (_equal) {
                        // This is the mid-chunk store it after extracting the timestamp
                        String receivingTime = String.valueOf(System.currentTimeMillis());
                        int headerLength = nrlBytes.length + receivingTime.getBytes().length + midHeaderBytes.length + 5 + 2; // +5 for segmentIndex and +2 for numberOfTiles
//                        byte[] sendingTimestamp = Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length, data.length - (midHeaderBytes.length + 5 + 2));
                        byte[] videoBytes = Arrays.copyOfRange(data, 0, data.length - headerLength);
//                        String sendingTime = new String(sendingTimestamp);

//                    String headerInfoString = new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length, data.length - (endHeaderBytes.length + 2)));
//                    System.out.println("Mid Header String: " + headerInfoString + " Timestamp: " + sendingTime) ;

                        // Logging network lag in a file
//                        String s = "Network-Lag: " + this.receiverId + " " + (Long.parseLong(receivingTime) - Long.parseLong(sendingTime)) + "\n";
//                        System.out.print(s);
//                        Files.write(Paths.get(this.networkLogFileName), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                        fileBytes.write(videoBytes);

                        // Sending back the stats
//                        String stats = len + "_" + sendingTime + "_" + this.stats.delayInFGAndBGSegments; // received chunk size and timestamp
//                        this.controlChannelHandler.sendStats(stats);

                    } else {
                        // Bifurcated mid-chunk without our header generated from SOCAT
                        fileBytes.write(data);
                    }
                }
            } else {
//                System.out.println(this.receiverId + " Socket is closed");
                break;
            }
        }
    }

    @Override
    public void run() {
        try {
            receiveFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}


class StitcherHelper {
    private String res;
    private TileDims tiles;

    public StitcherHelper(String resolution){
        res = resolution;
        tiles = new TileDims();
    }

    public void stitchFrame(Mat source_frame, Mat target_frame, String[] tiles){
        System.out.println("Dim :" + Arrays.toString(tiles));
        for(int i = 0; i < tiles.length; i++){
            int[] dim = this.tiles.getTileDims(res, Integer.parseInt(tiles[i]));
            source_frame.colRange(dim[0], dim[2]).rowRange(dim[1], dim[3]).copyTo(target_frame.colRange(dim[0], dim[2]).rowRange(dim[1], dim[3]));
        }
    }
}


class PlayerHelper implements Runnable {
    private EvictingQueue primaryQueue;
    private EvictingQueue helperQueue;
    private StitcherHelper stitcher;
    private String[] previousBackground = null;
    ArrayList<Future> results;
    Stats stats = new Stats();
    private long lastRenderTime = 0;
    private String logFile;
    private Mat cachedTiles;
    public PlayerHelper(String resolution, EvictingQueue pQueue, EvictingQueue hQueue, ArrayList<Future> results, Stats stats, String logFile) {
        this.primaryQueue = pQueue;
        this.helperQueue = hQueue;
        this.stitcher = new StitcherHelper(resolution);
        this.results = results;
        this.stats = stats;
        this.logFile = logFile;
    }

    void play(){
        while (true) {
            // NOTE: Primary queue always contains FG tiles
//            System.out.println("Both Channels status: " + this.results.get(0).isDone()  + " " + this.results.get(1).isDone() + " " + (this.results.get(0).isDone() || this.results.get(1).isDone()));
            boolean onlyFG = false;
            String[] helperStrings; String[] primaryStrings;
            try {
                while (true) {
                    // Waits until FG becomes available
                    String primaryString = primaryQueue.take();
                    if (primaryString != null) {
                        primaryStrings = primaryString.split("_");
                        break;
                    }
                }
                String helperString = helperQueue.take(); // Waits for 40ms for BG to be available

                String fgFileName; String bgFileName = null; String[] t = null; String[] y = null;
                int fgFileIndex = -1; int bgFileIndex = -1;
                y = primaryStrings[1].split(",");
                y = Arrays.copyOf(y, y.length + 1);
                y[y.length - 1] = "00"; // Adding tile "00" manually

                if (helperString != null) {
                    // Renders with previous BG if available
                    helperStrings = helperString.split("_");
                    while (true) {
                        // Synchronizing FG and BG Tiles
                        fgFileIndex = Integer.parseInt(primaryStrings[2]);
                        bgFileIndex = Integer.parseInt(helperStrings[2]);
//                        System.out.println("Synchronizing: " + fgFileIndex + " " + bgFileIndex);
                        if (fgFileIndex > bgFileIndex) {
                            if (helperQueue.size() > 0) {
//                                System.out.println("Adjusted for Lag");
                                helperStrings = helperQueue.take().split("_");
                                this.previousBackground = helperStrings;
                            } else {
//                                System.out.println("Can't adjusted for Lag");
                                this.previousBackground = helperStrings;
                                break;
                            }
                        } else if (fgFileIndex < bgFileIndex) {
                            if (fgFileIndex > 1) {
                                String[] tmp = helperStrings;
                                helperStrings = this.previousBackground;
                                this.previousBackground = tmp;
//                                System.out.println("Adjusted for Lead");
                                break;
                            } else {
                                this.previousBackground = helperStrings;
//                                System.out.println("Can't adjusted for Lead");
                                break;
                            }

                        } else {
                            this.previousBackground = helperStrings;
                            break;
                        }
                    }

                    fgFileName = primaryStrings[0];
                    bgFileName = helperStrings[0];
                    t = helperStrings[1].split(",");
                    fgFileIndex = Integer.parseInt(primaryStrings[2]);
                    bgFileIndex = Integer.parseInt(helperStrings[2]);

                    if (t.length == 1 & t[0] == "00") {
                        onlyFG = true;
                    }
//                    System.out.println("BG available, rendering with it");
//                System.out.println("t: " + t.length + " " + t[0]);
                } else if (helperString == null && (this.results.get(0).isDone() || this.results.get(1).isDone())) {
                    // Any one channel is down. Render of all the tiles received over one path as FG
                    fgFileName = primaryStrings[0];
                    fgFileIndex = Integer.parseInt(primaryStrings[2]);
                    onlyFG = true;
//                    System.out.println("One channel down, rendering all tiles");
                } else if (helperString == null && this.previousBackground != null) {
                    // If no current BG but has previous render it
                    fgFileName = primaryStrings[0];
                    bgFileName = this.previousBackground[0];
                    t = this.previousBackground[1].split(",");
                    fgFileIndex = Integer.parseInt(primaryStrings[2]);
                    bgFileIndex = Integer.parseInt(this.previousBackground[2]);

                    if (t.length == 1 & t[0] == "00") {
                        onlyFG = true;
                    }
//                    System.out.println("No BG, rendering with last one");
                } else {
                    // For the first time, if no BG available render only FG
                    fgFileName = primaryStrings[0];
                    fgFileIndex = Integer.parseInt(primaryStrings[2]);
                    onlyFG = true;
//                    System.out.println("Rendering only FG");
                }

                VideoCapture captFg = new VideoCapture(fgFileName);
                VideoCapture captBg = null; Mat bgFrame = null;
                VideoCapture captCache = new VideoCapture(fgFileName);; // for caching all the tiles
                if (onlyFG == false){
                    captBg = new VideoCapture(bgFileName);
                    bgFrame = new Mat();
                }

                Mat fgFrame = new Mat();
                this.stats.delayInFGAndBGSegments = fgFileIndex - bgFileIndex;
//                System.out.println("Rendering: FG=" + fgFileName + " " + this.primaryQueue.size() + " BG=" + bgFileName + " " + this.helperQueue.size() + " Delay: " + this.stats.delayInFGAndBGSegments);
                long currentRenderTime = System.currentTimeMillis();
                String stall = "[SFB]: " + (currentRenderTime - this.lastRenderTime) + " " + fgFileIndex + " " + bgFileIndex + "\n";
//                System.out.println("Stall FG BG: " + stall);
                Files.write(Paths.get(this.logFile), stall.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                bool firstRun = true;
                while (true) {
                    boolean retFg = captFg.read(fgFrame);
//                    System.out.println("Return: " + retFg);
                    if(retFg ==true & firstRun == true){
                        System.out.println("Initialized Cache Matrix" + fgFileIndex);
                        fgFrame.copyTo(this.cachedTiles);
                        firstRun = false;
                    }

                    if (onlyFG == false) {
                        boolean retBg = captBg.read(bgFrame);
                        if (retBg) {
                            stitcher.stitchFrame(fgFrame, this.cachedTiles, y);
                            stitcher.stitchFrame(bgFrame, this.cachedTiles, t);
                            long t3 = System.currentTimeMillis();
                            stitcher.stitchFrame(this.cachedTiles, fgFrame, t);
                            long t4 = System.currentTimeMillis();
                            System.out.println("[Stitching Time]: " + (t4 - t3));
                        }
                    }
                    if (onlyFG) {
                        try{
                            stitcher.stitchFrame(fgFrame, this.cachedTiles, y);
                            System.out.println("Stitching FG and cached BG");
                            System.out.println("Used Cached tiles");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }                    
                    if (retFg) {
                        HighGui.imshow("MainWindow", this.cachedTiles);
                        HighGui.waitKey(40);
//                        System.out.println("Rendered");
                    } else {
                        break;
                    }
                }
                this.lastRenderTime = System.currentTimeMillis();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        play();
    }
}


public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        // Default parameters
        String networkLagFile = "src/main/java/org/example/Multipath_ours_Stall_video5_car_latest.txt";
        String primaryFolderName = "src/main/java/org/example/ReceivedFilesPrimary";
        String helperFolderName = "src/main/java/org/example/ReceivedFilesHelper";
        String serverIp = "192.168.226.151";
        String helperIp = "192.168.226.151";
//        String serverIp = "2401:4900:81fd:7159:cfe4:ae57:1ed1:d7cd";
//        String helperIp = "2409:40d0:b5:16e9:5f05:713e:f1a8:6faf";
//        String helperIp = "10.42.0.12";
//        String helperIp = "2401:4900:8202:1ee2:8120:5ed9:7c2c:fb9";
//                "2401:4900:4456:888d:9a62:77aa:69ef:b0e0";
        int serverPort = 6002;
        int helperPort = 7003;
        int rttHandlerPortPrimary = 8001;
        int rttHandlerPortHelper = 8002;
        int controlChannelHandlerPortPrimary = 8003;
        int controlChannelHandlerPortHelper = 8004;
        int timeoutOfRttHi = 2000;
        int evictingQueuesPollWaitTime = 40; // 40ms for 25 fps
        Stats stats = new Stats();           // Stores delay in FG and BG tiles being rendered, used for feedback to server

//        int cpuId = Affinity.getCpu();
//        System.out.println("Main Process CPU: " + cpuId);

        // Cleaning older files
        try {
            Process p = Runtime.getRuntime().exec("bash src/main/java/org/example/clean.sh " + networkLagFile);
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Receiving Queues to share file names between receiving and stitching threads
        EvictingQueue primaryQueue = new EvictingQueue(2, evictingQueuesPollWaitTime);
        EvictingQueue helperQueue = new EvictingQueue(2, evictingQueuesPollWaitTime);
        ExecutorService pool = Executors.newFixedThreadPool(6); // Creates a thread pool to manage all threads

        ////////////////////// Control Channels //////////////////////////////////
        // For RTT
        RTTHandler rttHandlerPrimary = new RTTHandler(serverIp, rttHandlerPortPrimary, timeoutOfRttHi, 0);
        RTTHandler rttHandlerHelper = new RTTHandler(helperIp, rttHandlerPortHelper, timeoutOfRttHi, 1);

        Thread rttPrimaryThread = new Thread(rttHandlerPrimary);
        Thread rttHelperThread = new Thread(rttHandlerHelper);

//        rttPrimaryThread.start();
//        rttHelperThread.start();
        pool.execute(rttPrimaryThread);
        pool.execute(rttHelperThread);

        // For bandwidth and chunk completion time
        ControlChannelHandler controlChannelHandlerPrimary = new ControlChannelHandler(serverIp, controlChannelHandlerPortPrimary, 0);
        ControlChannelHandler controlChannelHandlerHelper = new ControlChannelHandler(serverIp, controlChannelHandlerPortHelper, 1);

        ////////////////////// Video Channels ////////////////////////////////////
        Receiver helperRecv = new Receiver(helperIp, helperPort, primaryFolderName, helperFolderName, 1, primaryQueue, helperQueue, controlChannelHandlerHelper, networkLagFile, stats);
        Thread.sleep(1000);
        Receiver primaryRecv = new Receiver(serverIp, serverPort, primaryFolderName, helperFolderName,0, primaryQueue, helperQueue, controlChannelHandlerPrimary, networkLagFile, stats);
        ArrayList<Receiver> receivers = new ArrayList<>(Arrays.asList(primaryRecv, helperRecv));
        ArrayList<Future> results = new ArrayList<>();

        PlayerHelper player = new PlayerHelper("1280x720", primaryQueue, helperQueue, results, stats, networkLagFile);

        Thread primaryThread = new Thread(primaryRecv);
        Thread helperThread = new Thread(helperRecv);
        Thread playerThread = new Thread(player);

        results.add(pool.submit(primaryThread)); results.add(pool.submit(helperThread));
        pool.execute(playerThread);

//        while (true){
//            for (int conn = 0; conn < 2; conn++){
////                System.out.println("Status: " + conn + " " + results.get(conn).isCancelled() + " " + results.get(conn).isDone());
//                if (results.get(conn).isDone()){
//                    Thread.sleep(2000);
//                    if (conn == 0) {     // reconnects with primary
//                        System.out.println("Reconnecting: " + conn);
//                        primaryRecv = new Receiver(serverIp, serverPort, primaryFolderName, helperFolderName,0, primaryQueue, helperQueue, controlChannelHandlerPrimary, networkLagFile);
//                        System.out.println("Re-established connection with primary");
//                        receivers.set(conn, primaryRecv);
//                        pool.execute(new Thread(primaryRecv));
//                    }
//                    else {              // reconnects with secondary
//                        helperRecv = new Receiver(helperIp, helperPort, primaryFolderName, helperFolderName, 1, primaryQueue, helperQueue, controlChannelHandlerHelper, networkLagFile);
//                        System.out.println("Re-established connection with helper");
//                        receivers.set(conn, helperRecv);
//                        pool.execute(new Thread(helperRecv));
//                    }
//                }
//            }
//        }

//        primaryThread.start();
//        helperThread.start();
//        playerThread.start();
//
//        primaryThread.join();
//        helperThread.join();
//        playerThread.join();
//
//        rttPrimaryThread.join();
//        rttHelperThread.join();
    }
}
