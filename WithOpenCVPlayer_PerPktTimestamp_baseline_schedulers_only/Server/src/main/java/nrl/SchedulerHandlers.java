package nrl;

import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import static java.lang.Math.abs;


class Estimates {
    volatile float RTT = 0.0F;
    volatile float BW = 0.0F;
    volatile boolean coldStartPhaseRTT = true;
    volatile boolean coldStartPhaseBW = true;

    public boolean isColdStartPhaseRTT(){
        return this.coldStartPhaseRTT;
    }

    public boolean isColdStartPhaseBW(){
        return this.coldStartPhaseBW;
    }

    public float getRTT(){
        return this.RTT;
    }

    public float getBW(){
        return this.BW;
    }

    public void setColdStartPhaseRTT(boolean state){
        this.coldStartPhaseRTT = state;
    }

    public void setColdStartPhaseBW(boolean state){
        this.coldStartPhaseBW = state;
    }

    public void setRTT(float currentRTT){
        this.RTT = currentRTT;
    }

    public void setBW(float currentBW){
        this.BW = currentBW;
    }
}


class RunningHMCalculator {
    CircularFifoQueue<Float> cQueue;
    public float runningSum;
    public RunningHMCalculator(CircularFifoQueue<Float> queue){
        this.cQueue = queue;
    }

    public float getHM(){
        float sum = 0;
        for (float element : this.cQueue) {
            if (element == 0.0) {
                sum += 1;
            } else {
                sum += (1 / element);
            }
//            System.out.println(element + " Sum: " + sum);
//            System.out.println("Queue: " + this.cQueue);
        }
        this.runningSum = sum;
        return (cQueue.maxSize()/sum); // HM
    }

    public float getUpdatedHM(float newValue){
//        System.out.println(this.runningSum + " " + this.cQueue.peek() + " " + newValue + " " + (cQueue.maxSize()/this.runningSum));
        if (newValue == 0.0){
            this.runningSum = (this.runningSum - (1/this.cQueue.peek())) + 1; // 1/1; considering the newValue=1
        } else {
            this.runningSum = (this.runningSum - (1 / this.cQueue.peek())) + (1 / newValue);
        }
        return (cQueue.maxSize()/this.runningSum); // new HM
    }
}


class RTTHandler implements Runnable {
    int serverPort;
    private InetAddress clientIP;
    private int clientPort;
    int timeout;
    int id;
    int bufSize = 20;
    int timestampSendingInternal;
    CircularFifoQueue<Float> evictingQueueForHM;
    RunningHMCalculator runningHMCalculator;
    Estimates estimates;

    public RTTHandler(int serverPort, int timeout, int id, int timestampSendingInternal, int estimateWindowSize, Estimates estimates) throws IOException {
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
        this.timestampSendingInternal = timestampSendingInternal; // in ms
        this.evictingQueueForHM = new CircularFifoQueue(estimateWindowSize); // queue to store past RTTs to get running harmonic mean
        this.runningHMCalculator = new RunningHMCalculator(this.evictingQueueForHM);
        this.estimates = estimates;
    }

    public void connect() throws SocketException, UnknownHostException, InterruptedException {
        // Receive the "Hi" message from the client to get its IP and Port
        DatagramSocket socket = new DatagramSocket(this.serverPort);

        byte[] buf = new byte[this.bufSize];
        DatagramPacket dpSend;
        DatagramPacket dpReceive;

        // Getting Hi message
        try {
            dpReceive = new DatagramPacket(buf, buf.length);
            socket.receive(dpReceive);
            System.out.println(this.id + " Hi Received");

            // Get client IP and Port
            clientIP = dpReceive.getAddress();
            clientPort = dpReceive.getPort();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        int packetSequenceNumber = 0;
        BitSet bitArray = new BitSet();
        while (true) {
            // Sending timestamps
            long sentTimestamp = System.currentTimeMillis();
            long recvTimestamp = 0;
            buf = (String.valueOf(sentTimestamp) + String.format("%05d", packetSequenceNumber)).getBytes();
            byte[] recvBuf = new byte[buf.length];
            int bitIndexToSet = packetSequenceNumber % bitArray.size();           // index of bitArray to set for the current packet
            try {
                dpSend = new DatagramPacket(buf, buf.length, this.clientIP, this.clientPort);
                socket.send(dpSend);
                bitArray.set(bitIndexToSet, true);

                dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                socket.setSoTimeout(this.timeout);
                socket.receive(dpReceive);

                // Checks if somehow a lost packet (assumed) received
                int sequenceNumberOfReceivedPacket = Integer.parseInt(new String(recvBuf).substring(recvBuf.length - 5));
//                System.out.println(this.id + " Sent RTT packet: " + String.valueOf(sentTimestamp) + String.format("%05d", packetSequenceNumber) + " Received Seq Number: " + sequenceNumberOfReceivedPacket);
                if (sequenceNumberOfReceivedPacket == packetSequenceNumber) {
                    bitArray.set(bitIndexToSet, false);                                 // Resets the current packet in the bitArray as it is received
                    recvTimestamp = Long.parseLong(new String(recvBuf).substring(0, buf.length - 5));
                }
                else{
                    bitIndexToSet = sequenceNumberOfReceivedPacket % bitArray.size();
                    bitArray.set(bitIndexToSet, false);
                    boolean found = false;
                    for (int i = 0; i < 4; i++){                                        // Checks for the right packet only 4 times
                        try {
                            recvBuf = new byte[buf.length];
                            dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                            socket.setSoTimeout(10);                                    // Waits for a short while to check for any received packet
                            socket.receive(dpReceive);
                            sequenceNumberOfReceivedPacket = Integer.parseInt(new String(recvBuf).substring(buf.length - 5));
                            bitIndexToSet = sequenceNumberOfReceivedPacket % bitArray.size();
                            bitArray.set(bitIndexToSet, false);
//                            System.out.println("Inside:: Seq No.: " + sequenceNumberOfReceivedPacket);
                            if (sequenceNumberOfReceivedPacket == packetSequenceNumber) {
                                recvTimestamp = Long.parseLong(new String(recvBuf).substring(0, buf.length - 5));
                                found = true; break;
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("Rechecking receive buffer: " + i);
                        }
                    }
                    if (found == false){
                        continue;
                    }
                }

                long currentTimeStamp = System.currentTimeMillis();
                float rtt = currentTimeStamp - recvTimestamp;
                if (rtt == 0){
                    rtt = 1.0F;
                }

                // Cold start phase
                if (this.evictingQueueForHM.size() < this.evictingQueueForHM.maxSize()){
                    this.evictingQueueForHM.add(rtt);

                    // Normal Streaming Phase
                } else{
                    if (this.estimates.isColdStartPhaseRTT()) {
                        float rttHM = this.runningHMCalculator.getHM(); // calculates HM only once later just updates
                        this.estimates.setColdStartPhaseRTT(false);
                        this.estimates.setRTT(rttHM);
//                        System.out.println(this.id + " Cold start phase for RTT");
                    }
                    else{
                        float rttHM = this.runningHMCalculator.getUpdatedHM(rtt);
                        this.evictingQueueForHM.add(rtt);
                        this.estimates.setRTT(rttHM);
                        System.out.println(this.id + " Current RTT (ms): " + rtt + ", RTT HM (ms): " + rttHM);
                    }
                }

                // Sleep before next send
                Thread.sleep(this.timestampSendingInternal);

            } catch (SocketTimeoutException e) {
                System.out.println(this.id + " Resending Timestamp");
                // Socket timeout; assuming the RTT packet got lost.
                bitArray.set(bitIndexToSet, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            packetSequenceNumber += 1;
        }
    }

    @Override
    public void run() {
        try {
            connect();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}


class ControlChannelHandler implements Runnable {
    DatagramSocket socket;
    int bufSize = 40;
    int id;
    CircularFifoQueue<Float> evictingQueueForHM;
    RunningHMCalculator runningHMCalculator;
    Estimates estimates;
    private float lastPositiveBW = 100; // initializing with 100Kbps

    ControlChannelHandler(int recvPort, int id, int estimateWindowSize, Estimates estimates) throws IOException {
        socket = new DatagramSocket(recvPort);
        this.id = id;
        this.evictingQueueForHM = new CircularFifoQueue(estimateWindowSize);        // queue to store past BWs to get running harmonic mean
        this.runningHMCalculator = new RunningHMCalculator(this.evictingQueueForHM);
        this.estimates = estimates;
    }

    public void recvStats() throws IOException {
        float bandwidth = this.lastPositiveBW;
        while (true) {
            byte[] buf = new byte[this.bufSize];
            DatagramPacket dpReceive = new DatagramPacket(buf, buf.length);
            this.socket.receive(dpReceive);

            long recvTimestamp = System.currentTimeMillis();
            String receivedStats = new String(Arrays.copyOfRange(buf, 0, dpReceive.getLength()));
            String[] splitStats = receivedStats.split("_"); // stats are like "filesize_timestamp"
            float completionTime = (recvTimestamp - Long.parseLong(splitStats[1])); // in ms

            String s = this.id + " [RTT(ms)_FS(bits)_CT(ms)] " + estimates.getRTT() + " " + (Integer.parseInt(splitStats[0]) * 8) + " " + (recvTimestamp - Long.parseLong(splitStats[1])) + "\n";
            Files.write(Paths.get("src/main/java/nrl/tput_car.txt"), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (completionTime < 0) {
                bandwidth = this.lastPositiveBW;
            }
            else {
                bandwidth = (Integer.parseInt(splitStats[0]) * 8) / completionTime; // filesize(Bytes)/totaltime(ms) = Kbps bandwidth
                this.lastPositiveBW = bandwidth;
            }

            // Cold start phase
            if (this.evictingQueueForHM.size() < this.evictingQueueForHM.maxSize()){
                this.evictingQueueForHM.add(bandwidth);
//                System.out.println("Storing: " + this.evictingQueueForHM.size());

                // Normal Streaming Phase
            } else{
                if (this.estimates.isColdStartPhaseBW()) {
                    float bwHM = this.runningHMCalculator.getHM(); // calculates HM only once later just updates
                    this.estimates.setColdStartPhaseBW(false);
                    this.estimates.setBW(bwHM);
                }
                else{
                    float bwHM = this.runningHMCalculator.getUpdatedHM(bandwidth);
                    this.evictingQueueForHM.add(bandwidth);
                    this.estimates.setBW(bwHM);
                    System.out.println(this.id + " Current BW (Kbps): " + bandwidth + ", BW HM (Kbps): " + bwHM + " Chunk completion time (ms): " + completionTime);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            recvStats();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}


class Scheduler {
    String schedulerType;
    Estimates estimatesFastPath;
    Estimates estimatesSlowPath;
    int totalTiles = 16;
    float qpBitrateDelta = 1.2F;        // factor by which bitrate drops on increasing QP by step size of 3
    int lastUsedQP = 24;
    public Scheduler(String schedulerType, Estimates estimatesFastPath, Estimates estimatesSlowPath){
        // type = "minRTT" or "minCT" or "musher" or "none" or "BFlow"
        // fastpath = primary path and slowpath = secondary path via helper
        this.schedulerType = schedulerType;
        this.estimatesFastPath = estimatesFastPath;
        this.estimatesSlowPath = estimatesSlowPath;
    }

    public float getCompletionTime(int totalFileSize, float bandwidth, float rtt){
        // returns the completion of the given segment or tiles
        return (rtt/2) + (totalFileSize/bandwidth);
    }

    public int getQpToUse(long filesize){
        float totalBW = this.estimatesFastPath.getBW() + this.estimatesSlowPath.getBW();
        // If the last QP was not 24 then figuring out
        // what would have been the bitrate if was 24 actually
        float currentBR = (float) (filesize/0.16);              // FS = BR * videoDuration; Here duration is 160ms for 4 frame at 25 fps
        int diff = (this.lastUsedQP - 24)/3;
        float multiplyFactor = diff * this.qpBitrateDelta;
        if (multiplyFactor != 0){
            currentBR = currentBR * multiplyFactor;             // Upscaling bitrate to 24 QP if QP was more than 24
        }
        int qp = 24;
        for (int i = 24; i < 40; i+=3){ // QPs to use 24, 27, 30, 33, 36, 39
            System.out.println(currentBR + "i: "+ i);
            if (currentBR < totalBW) {  // Uses the QP which satisfies the current BW
                qp = i;
                break;
            } else if (i == 39) {       // Returns the highest QP if all others doesn't work
                qp = i;
            }
            currentBR /= this.qpBitrateDelta;
        }
        this.lastUsedQP = qp;
        return qp;
    }

    public String getSchedule(String infoString){ // infoString = "numTilesInFG_FGTilesFileSize_numTilesInBG_BGTilesFileSize"
        String outString = null;
        String[] splits = infoString.split("_");
        String numFGTiles = splits[0];
        int FGTilesFilesize = Integer.parseInt(splits[1]);
        String numBGTiles = splits[2];
        int BGTilesFilesize = Integer.parseInt(splits[3]);
        float fastPathRTT = this.estimatesFastPath.getRTT(); // fast means primary and slow means secondary path
        float slowPathRTT = this.estimatesSlowPath.getRTT();
        float fastPathBW = this.estimatesFastPath.getBW();
        float slowPathBW = this.estimatesSlowPath.getBW();

        // fast path means primary and slow path means secondary
        if (this.schedulerType == "minRTT"){
            if (fastPathRTT <= slowPathRTT){
                // output format = "numFGTile_pathToSchedule_numBGTiles_pathToSchedule_changeQP"
                // if changeQP = 0 then increase QP else not
                // 0 mean primary and 1 means secondary
                outString = numFGTiles + "_0_" + numBGTiles + "_1";
            } else{
                outString = numFGTiles + "_1_" + numBGTiles + "_0";
            }
        }
        else if (this.schedulerType == "minCT"){ // minimum completion time
            // Chooses the path with minimum completion time
            float completionTimeOfPrimaryPath = this.getCompletionTime(FGTilesFilesize, fastPathBW, fastPathRTT);
            float completionTimeOfSecondaryPath = this.getCompletionTime(FGTilesFilesize, slowPathBW, slowPathRTT);
            if (completionTimeOfPrimaryPath <= completionTimeOfSecondaryPath){
                // output format = "numFGTile_pathToSchedule_numBGTiles_pathToSchedule"
                // 0 mean primary and 1 means secondary
                outString = numFGTiles + "_0_" + numBGTiles + "_1";
            } else{
                outString = numFGTiles + "_1_" + numBGTiles + "_0";
            }
        }
        else if (this.schedulerType == "BFlow") { // Balanced flow
            // Streams the tiles over both path by balancing the
            // equation (BW1/Sx + RTT1/2) - (BW2/S(16-x) + RTT2/2) = 0
            // Here, x if the number of tiles to send over the chosen path,
            // S is the size of one tile, assuming each has the same size
            // BW1, BW2, RTT1, and RTT2 are bandwidths and RTTs of paths 1 and 2 respectively.
            float minimumDifference = 0;
            int tilesToSendThroughPrimaryPath = 0;
            int sizeOfOneTile = (FGTilesFilesize + BGTilesFilesize) / this.totalTiles;
            for (int i = 2; i < this.totalTiles; i++) {
                float completionTimeOfPath1 = this.getCompletionTime((sizeOfOneTile * i), fastPathBW, fastPathRTT);
                float completionTimeOfPath2 = this.getCompletionTime((sizeOfOneTile * (this.totalTiles - i)), slowPathBW, slowPathRTT);

                float diff = abs(completionTimeOfPath2 - completionTimeOfPath1);
                if ((minimumDifference > diff) | (i == 2)) {
                    minimumDifference = diff;
                    tilesToSendThroughPrimaryPath = i;
                }
//                System.out.println("Difference: " + diff + " i: " + i + " tilesOverPrimaryPath: " + tilesToSendThroughPrimaryPath);
            }
            // output format = "numFGTile_pathToSchedule_numBGTiles_pathToSchedule"
            // 0 mean primary and 1 means secondary
            outString = tilesToSendThroughPrimaryPath + "_0_" + (this.totalTiles - tilesToSendThroughPrimaryPath) + "_1";
        }
        else if (this.schedulerType == "musher") {
            // Musher distributes packets based on the throughput of the two interfaces
            // currRatio is the ratio of the throughput of the two interfaces

            float currRatio = (fastPathBW / (fastPathBW + slowPathBW)); // ratio of throughputs

            int numPrimaryPathTiles = (int) Math.ceil(currRatio * this.totalTiles);
            if (numPrimaryPathTiles == 16) {        // always caps the FG tiles to 15 only
                numPrimaryPathTiles = 15;
            } else if (numPrimaryPathTiles == 1) {  // minimum cap of 2
                numPrimaryPathTiles = 2;
            }
            int numSecondaryPathTiles = this.totalTiles - numPrimaryPathTiles;
            if (fastPathBW > slowPathBW) {
                outString = numPrimaryPathTiles + "_0_" + numSecondaryPathTiles + "_1";
            } else {
                outString = numPrimaryPathTiles + "_1_" + numSecondaryPathTiles + "_0";
            }
            System.out.println("Musher: " + currRatio + " " + outString);
        }
        else if (this.schedulerType == "none"){
            outString = numFGTiles + "_0_" + numBGTiles + "_1";
        }
        else {
            System.out.println("Invalid Scheduler Type");
            IOException e = new IOException();
            throw new RuntimeException(e);
        }
        return outString;
    }
}
