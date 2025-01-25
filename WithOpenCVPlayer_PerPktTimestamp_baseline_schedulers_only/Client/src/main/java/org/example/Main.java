package org.example;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

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

    public int getDrops(){
        return this.drops;
    }

    public String take() throws IOException, InterruptedException {
        return this._queue.take();
    }

    public int size(){
        return this._queue.size();
    }
}


class Receiver implements Runnable {
    InetSocketAddress socketAddress;
    SctpChannel receiverChannel;
    String fileSavingDirectory;
    int receiverId;
    static byte[] nrlBytes;
    static byte[] endHeaderBytes;
    static byte[] midHeaderBytes;
    int receiveBufferSize = 2500;
    EvictingQueue receiverQueue;
    ControlChannelHandler controlChannelHandler;
    String networkLogFileName;

    public Receiver(String serverIp, int serverPort, String fileDirPath, int receiverId, EvictingQueue queue, ControlChannelHandler controlChannelHandler, String networkLogFileName) throws IOException {
        socketAddress = new InetSocketAddress(serverIp, serverPort);
        receiverChannel = SctpChannel.open(socketAddress, 1, 1);

        String reverseLastCharacters = "\nNRL\n";   // Identifier to segragate our timestamps
        String midCharacters = "\nMID\n";           // To detect segments chunks that are no the last
        String endCharacters = "\nEND\n";           // To detect last segment chunk
        nrlBytes = reverseLastCharacters.getBytes();
        midHeaderBytes = midCharacters.getBytes();
        endHeaderBytes = endCharacters.getBytes();

        this.fileSavingDirectory = fileDirPath;
        this.networkLogFileName = networkLogFileName;
        this.receiverId = receiverId;
        this.receiverQueue = queue;
        this.controlChannelHandler = controlChannelHandler;
    }

    void receiveFile() throws IOException {
        ByteBuffer rxBuffer;
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        long videoSegmentSize = 0; int counter = 0;

        while (true) {
            MessageInfo messageInfo = receiverChannel.receive(rxBuffer = ByteBuffer.allocateDirect(receiveBufferSize), null, null);
            int len = messageInfo.bytes();
            if (len == -1){break;}
//            System.out.println("[Receiver: " + this.receiverId + "] Total bytes received " + len);
            rxBuffer.flip();
            byte[] data = new byte[len];
            rxBuffer.get(data);
            rxBuffer.clear();
            videoSegmentSize += len;        // used to get total video segment size in bytes

            byte[] headerSlice = Arrays.copyOfRange(data, data.length - (endHeaderBytes.length + 2), (data.length - 2)); // +2 for #ofTiles added to header string
//            System.out.println(this.receiverId + " Header Slice: " + new String(headerSlice));
            boolean equal = Arrays.equals(headerSlice, endHeaderBytes);
            if (equal) {
                // If the chunk is the last one then save the segment
                String receivingTime = String.valueOf(System.currentTimeMillis());
                int timestampLength = receivingTime.getBytes().length;
                int numTiles = Integer.parseInt(new String(Arrays.copyOfRange(data, data.length - 2, data.length)));
//                System.out.println("Num Tiles: " + numTiles);
                int headerInfoStringSize = 4 + (numTiles * 2) + (numTiles - 1); // \NRL\ntimestamp_0_02,04,15,16_\nEND\n; infoString = _0_02,04,15,16_
                int headerLength = nrlBytes.length + timestampLength + headerInfoStringSize + endHeaderBytes.length + 2;
                String headerInfoString = new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length + timestampLength, data.length - (endHeaderBytes.length + 2 + 1))); // +1 to remove last "_"
                byte[] sendingTimestamp = Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length, data.length - (endHeaderBytes.length + 2 + headerInfoStringSize));
                byte[] videoBytes = Arrays.copyOfRange(data, 0, data.length - headerLength);
                String sendingTime = new String(sendingTimestamp);

                // Logging network lag in a file
//                String s = "Network-Lag: " + this.receiverId + " " + (Long.parseLong(receivingTime) - Long.parseLong(sendingTime)) + "\n";
//                System.out.print(s);
//                Files.write(Paths.get(this.networkLogFileName), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

//                System.out.println("End Header String: " + headerInfoString + " Timestamp: " + sendingTime);
                fileBytes.write(videoBytes);

                // Sending back the stats
                String stats = videoSegmentSize + "_" + sendingTime; // receivedChunkSize_timestamp
                this.controlChannelHandler.sendStats(stats);
                videoSegmentSize = 0;

                try (OutputStream outputStream = new FileOutputStream(fileSavingDirectory + "/file" + counter + ".mp4")) {
                    fileBytes.writeTo(outputStream);
                    this.receiverQueue.add(fileSavingDirectory + "/file" + counter + ".mp4" + headerInfoString);
//                    System.out.println("Queue Size: " + receiverQueue.size());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fileBytes.reset();
                counter++;
            }
            else{
                boolean _equal = Arrays.equals(headerSlice, midHeaderBytes);
                if(_equal){
                    // This is the mid-chunk store it after extracting the timestamp
                    String receivingTime = String.valueOf(System.currentTimeMillis());
                    int headerLength = nrlBytes.length + receivingTime.getBytes().length + midHeaderBytes.length + 2; // + 2 for #ofTiles
                    byte[] videoBytes = Arrays.copyOfRange(data, 0, data.length - headerLength);

                    // Logging network lag in a file
//                    String s = "Network-Lag: " + this.receiverId + " " + (Long.parseLong(receivingTime) - Long.parseLong(sendingTime)) + "\n";
//                    System.out.print(s);
//                    Files.write(Paths.get(this.networkLogFileName), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                    fileBytes.write(videoBytes);

                    // Sending back the stats
//                    String stats = len + "_" + sendingTime; // received chunk size and timestamp
//                    this.controlChannelHandler.sendStats(stats);

                } else {
                    // Bifurcated mid-chunk without our header generated from SOCAT
                    fileBytes.write(data);
                }
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
    private long lastRenderTime = 0;
    private String logFile;

    public PlayerHelper(String resolution, EvictingQueue pQueue, EvictingQueue hQueue, String logFile) {
        primaryQueue = pQueue;
        helperQueue = hQueue;
        stitcher = new StitcherHelper(resolution);
        this.logFile = logFile;
    }

    void play(){
        while (true) {
            try {
                int fgSize = this.primaryQueue.size();
                int bgSize = this.helperQueue.size();
                String[] primaryStrings = primaryQueue.take().split("_");
                String[] helperStrings = helperQueue.take().split("_");

                String fgFileName; String bgFileName; String[] t;
                if (Integer.parseInt(primaryStrings[1]) == 0) {
                    fgFileName = primaryStrings[0];
                    bgFileName = helperStrings[0];
                    t = helperStrings[2].split(",");
                } else{
                    bgFileName = primaryStrings[0];
                    fgFileName = helperStrings[0];
                    t = primaryStrings[2].split(",");
                }

                VideoCapture captFg = new VideoCapture(fgFileName);
                VideoCapture captBg = new VideoCapture(bgFileName);
                Mat fgFrame = new Mat(); Mat bgFrame = new Mat();

                long currentRenderTime = System.currentTimeMillis();
                String stall = "[SFB]: " + (currentRenderTime - this.lastRenderTime) + "\n" +
                        "[Drops]: " + this.primaryQueue.getDrops() + " " + this.helperQueue.getDrops() + "\n" +
                        "[BufferLevel]: " + fgSize + " " + bgSize + "\n";
                System.out.println("Stall: " + stall);
                Files.write(Paths.get(this.logFile), stall.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);


                while (true) {
                    boolean retFg = captFg.read(fgFrame);
                    boolean retBg = captBg.read(bgFrame);
                    if (retFg && retBg) {
//                        long t3 = System.currentTimeMillis();
                        stitcher.stitchFrame(bgFrame, fgFrame, t);
//                        long t4 = System.currentTimeMillis();
//                        System.out.println("Stitching Time: " + (t4 -t3));
                        HighGui.imshow("MainWindow", fgFrame);
                        HighGui.waitKey(25);
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
        String networkLogFile = "src/main/java/org/example/Multipath_tilelevel_bflow_walk_video5.txt";
        String primaryFolderName = new String("src/main/java/org/example/ReceivedFilesPrimary");
        String helperFolderName = new String("src/main/java/org/example/ReceivedFilesHelper");
        String serverIp = "192.168.226.151";
        String helperIp = "192.168.226.151";
        int serverPort = 6002;
        int helperPort = 7003;
        int rttHandlerPortPrimary = 8001;
        int rttHandlerPortHelper = 8002;
        int controlChannelHandlerPortPrimary = 8003;
        int controlChannelHandlerPortHelper = 8004;
        int timeoutOfRttHi = 2000;

        // Cleaning older files
        try {
            Process p = Runtime.getRuntime().exec("bash src/main/java/org/example/clean.sh " + networkLogFile);
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Receiving Queues to share file names between receiving and stitching threads
        EvictingQueue primaryQueue = new EvictingQueue(2);
        EvictingQueue helperQueue = new EvictingQueue(2);

        ////////////////////// Control Channels //////////////////////////////////
        // For RTT
        RTTHandler rttHandlerPrimary = new RTTHandler(serverIp, rttHandlerPortPrimary, timeoutOfRttHi, 0);
        RTTHandler rttHandlerHelper = new RTTHandler(helperIp, rttHandlerPortHelper, timeoutOfRttHi, 1);

        Thread rttPrimaryThread = new Thread(rttHandlerPrimary);
        Thread rttHelperThread = new Thread(rttHandlerHelper);

        rttPrimaryThread.start();
        rttHelperThread.start();

        // For bandwidth and chunk completion time
        ControlChannelHandler controlChannelHandlerPrimary = new ControlChannelHandler(serverIp, controlChannelHandlerPortPrimary, 0);
        ControlChannelHandler controlChannelHandlerHelper = new ControlChannelHandler(serverIp, controlChannelHandlerPortHelper, 1);

        ////////////////////// Video Channels ////////////////////////////////////
        Receiver primaryRec = new Receiver(serverIp, serverPort, primaryFolderName, 0, primaryQueue, controlChannelHandlerPrimary, networkLogFile);
        Receiver helperRec = new Receiver(helperIp, helperPort, helperFolderName, 1, helperQueue, controlChannelHandlerHelper, networkLogFile);

        PlayerHelper player = new PlayerHelper("1280x720", primaryQueue, helperQueue, networkLogFile);

        Thread primaryThread = new Thread(primaryRec);
        Thread helperThread = new Thread(helperRec);
        Thread playerThread = new Thread(player);

        primaryThread.start();
        helperThread.start();
        playerThread.start();

        primaryThread.join();
        helperThread.join();
        playerThread.join();

        rttPrimaryThread.join();
        rttHelperThread.join();
    }
}
