package org.example;

import java.io.*;
import java.lang.reflect.Array;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
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

class SegmentMap {

    private ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, byte[]>> segmentMap;

    public SegmentMap() {
        this.segmentMap = new ConcurrentHashMap<>();
    }

    public void initializeInnerMap(Integer segmentNumber) {
        if (this.segmentMap.containsKey(segmentNumber) == false) {
            ConcurrentHashMap<Integer, byte[]> newPacketMap = new ConcurrentHashMap<>();
            this.segmentMap.put(segmentNumber, newPacketMap);
//            System.out.println("Initialized with key: " + segmentNumber);
        }
    }

    public ConcurrentHashMap<Integer, byte[]> get(Integer segmentNumber) {
        return this.segmentMap.get(segmentNumber);
    }

    public byte[] getMapBytes(Integer segmentNumber, Integer sequenceNumber) {
        return this.segmentMap.get(segmentNumber).get(sequenceNumber);
    }

    public void remove(int segmentNumber) {
        this.segmentMap.remove(segmentNumber);
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
    SegmentMap segmentMap;

    public Receiver(String serverIp, int serverPort, String fileDirPath, int receiverId, EvictingQueue queue, SegmentMap segmentMap, ControlChannelHandler controlChannelHandler, String networkLogFileName) throws IOException {
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
        this.segmentMap = segmentMap;
        this.controlChannelHandler = controlChannelHandler;
    }

    void receiveFile() throws IOException {
        ByteBuffer rxBuffer;
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        int counter = 0; long videoSegmentSize = 0;

        while (true) {
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

//            System.out.println(this.receiverId + " Header Slice: " + new String(headerSlice));
            String receivingTime = String.valueOf(System.currentTimeMillis());
            int timestampLength = receivingTime.getBytes().length;
            int headerLength = nrlBytes.length + timestampLength + 12 + endHeaderBytes.length; //+(5+4+3=12) for 5 digit segment number , 4 digit sequence number and 3 digit for total packets for the segment
            int sequenceNumber = Integer.parseInt(new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length + timestampLength + 5, data.length - endHeaderBytes.length - 3)));
            int segmentNumber = Integer.parseInt(new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length + timestampLength, data.length - endHeaderBytes.length - 7)));  // (4+3=7)
            int totalPackets = Integer.parseInt(new String(Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length + timestampLength + 9, data.length - endHeaderBytes.length)));
            byte[] sendingTimestamp = Arrays.copyOfRange(data, (data.length - headerLength) + nrlBytes.length, data.length - (endHeaderBytes.length + 12));
            byte[] videoBytes = Arrays.copyOfRange(data, 0, data.length - headerLength);
            String sendingTime = new String(sendingTimestamp);
//            System.out.println("Sequence Number: " + sequenceNumber + " Segment Number: " + segmentNumber + " " + totalPackets + " " +  this.receiverId);

//            System.out.println("Initializing new segment map with segment number: " + segmentNumber);
            segmentMap.initializeInnerMap(segmentNumber);

            if (this.segmentMap.get(segmentNumber).size() == totalPackets - 1) {
                // If the chunk is the last one then save the segment
                segmentMap.get(segmentNumber).put(sequenceNumber, videoBytes);

                // Logging network lag in a file
//                String s = "Network-Lag: " + this.receiverId + " " + (Long.parseLong(receivingTime) - Long.parseLong(sendingTime)) + "\n";
//                System.out.print(s);
//                Files.write(Paths.get(this.networkLogFileName), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

//                System.out.println("End Header String: " + headerInfoString + " Timestamp: " + sendingTime);
//                fileBytes.write(videoBytes);

//                System.out.println("Size of array before saving: " + fileBytes.size());

                System.out.println("Size: " + this.segmentMap.get(segmentNumber).size() + " " + segmentNumber);
//
                for (int i = 0; i < this.segmentMap.get(segmentNumber).size(); i++) {
                    byte[] toWriteBytes = this.segmentMap.getMapBytes(segmentNumber, i);
//                    System.out.println(this.segmentMap.get(segmentNumber).toString() + " " + "Segment Number :" + segmentNumber);

//                    System.out.println("Packet size to write: " + toWriteBytes.length);
                    if(toWriteBytes!=null) {
                        fileBytes.write(toWriteBytes);
                    }else{
                        System.out.println("************************************************************** NULL ***************************************************");
                    }
                }
//                System.out.println("Size of array to save: " + fileBytes.size());
                segmentMap.remove(segmentNumber);

                // Sending back the stats
                String stats = videoSegmentSize + "_" + sendingTime + "_" + segmentNumber; // this.stats.delayInFGAndBGSegments; // receivedChunkSize_timestamp_segmentIndex
                this.controlChannelHandler.sendStats(stats);
                videoSegmentSize = 0;

                try {
//                    String[] headerStrings = headerInfoString.split("_");
                    fileBytes.writeTo(new FileOutputStream(fileSavingDirectory + "/file" + counter + ".mp4"));
                    this.receiverQueue.add(fileSavingDirectory + "/file" + counter + ".mp4");
//                    System.out.println("Queue Size: " + receiverQueue.size());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fileBytes.reset();
                counter++;
            } else {
                // This is the mid-chunk store it after extracting the timestamp
                segmentMap.get(segmentNumber).put(sequenceNumber, videoBytes);

                // Sending back the stats
//                String stats = len + "_" + sendingTime; // received chunk size and timestamp
//                this.controlChannelHandler.sendStats(stats);
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


class PlayerHelper implements Runnable {
    EvictingQueue fileNameQueue;
    int interFrameDelay;
    private long lastRenderTime = 0;
    private String logFile;

    public PlayerHelper(EvictingQueue queue, int _interFrameDelay, String logFile) {
        fileNameQueue = queue;
        interFrameDelay = _interFrameDelay;
        this.logFile = logFile;
        HighGui.namedWindow("MainWindow");
    }

    void play() throws IOException {
        while (true) {
            try {
//                long player_t1 = System.currentTimeMillis();
                String fileName = fileNameQueue.take();
                VideoCapture capt = new VideoCapture(fileName);
                Mat frame = new Mat();

                long currentRenderTime = System.currentTimeMillis();
//                String s = "[Drops]: " + this.fileNameQueue.getDrops() + " " + this.fileNameQueue.getDrops() + "\n" +
//                        "[BufferLevel]: " + this.fileNameQueue.size() + "\n";
                String stall = "[SFB]: " + (currentRenderTime - this.lastRenderTime) + "\n";
                System.out.println("Stall: " + stall);
                Files.write(Paths.get(this.logFile), stall.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                while (true) {
//                    long player_t2 = System.currentTimeMillis();
                    boolean ret = capt.read(frame);
                    if (ret) {
                        HighGui.imshow("MainWindow", frame);
                        HighGui.waitKey(interFrameDelay);
//                        long player_t3 = System.currentTimeMillis();
//                          System.out.println(fileName + " Cap time: " + (player_t2 - player_t1) + " Decoding + Rendering Time: " + (player_t3 - player_t2));
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
        try {
            play();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}


public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        // Default parameters
        String networkLagFile = "src/main/java/org/example/Multipath_pkt_level_bflow_walk_video5_new.txt";
        String folderName = "src/main/java/org/example/ReceivedFiles";
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
            Process p = Runtime.getRuntime().exec("bash src/main/java/org/example/clean.sh " + networkLagFile);
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Receiving Queues to share file names between receiving and stitching threads
        EvictingQueue queue = new EvictingQueue(2);

        // Map used for storing segment along with their sequence-wise packets
        SegmentMap segmentMap = new SegmentMap();

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
        Receiver primaryRecv = new Receiver(serverIp, serverPort, folderName, 0, queue, segmentMap, controlChannelHandlerPrimary, networkLagFile);
        Receiver helperRecv = new Receiver(helperIp, helperPort, folderName, 1, queue, segmentMap, controlChannelHandlerHelper, networkLagFile);

        PlayerHelper player = new PlayerHelper(queue, 40, networkLagFile);

        Thread primaryThread = new Thread(primaryRecv);
        Thread helperThread = new Thread(helperRecv);
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
