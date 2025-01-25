package nrl;

import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import static java.lang.Math.*;


class Estimates {
    volatile float RTT = 0.0F;
    volatile float BW = 0.0F;
    private volatile boolean coldStartPhaseRTT = true;
    private volatile boolean coldStartPhaseBW = true;
    private volatile boolean isChannelDown = false;
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

    public boolean getChannelStatus(){
        return this.isChannelDown;
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

    public void setChannelStatus(boolean value){
        this.isChannelDown= value;
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
//            System.out.println(this.id + " Hi Received");

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
                bitArray.set(bitIndexToSet, true);                                  // Marks the current packet sent in the bitArray

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
//                            System.out.println("Rechecking receive buffer: " + i);
                            ; // pass, do nothing
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
                    else {
//                        System.out.println(this.id + " Channel Status: " + (estimates.getChannelStatus() == false));
                        if (estimates.getChannelStatus() == false) {    // channel is up
                            float rttHM = this.runningHMCalculator.getUpdatedHM(rtt);
                            this.evictingQueueForHM.add(rtt);
                            this.estimates.setRTT(rttHM);
                            System.out.println(this.id + " Current RTT (ms): " + rtt + ", RTT HM (ms): " + rttHM);
                        }
                        else {                                          // channel is down
                            this.estimates.setRTT(10000.0F);            // Makes RTT very high to signal scheduler that the channel is down
                            System.out.println(this.id + " Channel down made RTT high");
                        }
                    }
                }

                // Sleep before next send
                Thread.sleep(this.timestampSendingInternal);

            } catch (SocketTimeoutException e) {
//                System.out.println(this.id + " Resending Timestamp");
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
    private float lastPositiveBW = 100; // initializing with 100Kbps
    Estimates estimates;

    ControlChannelHandler(int recvPort, int id, int estimateWindowSize, Estimates estimates) throws IOException {
        socket = new DatagramSocket(recvPort);
        this.id = id;
        this.evictingQueueForHM = new CircularFifoQueue(estimateWindowSize); // queue to store past RTTs to get running harmonic mean
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
//            System.out.println(this.id + " Stats Received: " + receivedStats);
            String[] splitStats = receivedStats.split("_"); // stats are like "filesize_timestamp_segmentIndex"
            float completionTime = (recvTimestamp - Long.parseLong(splitStats[1])); // in ms
            String s = this.id + " [RTT(ms)_FS(bits)_CT(ms)] " + estimates.getRTT() + " " + (Integer.parseInt(splitStats[0]) * 8) + " " + (recvTimestamp - Long.parseLong(splitStats[1])) + "\n";
            Files.write(Paths.get("src/main/java/nrl/tput_bus.txt"), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (completionTime < 0) {
                bandwidth = this.lastPositiveBW;
//                System.out.println("Negative bandwidth: " + completionTime + " " + estimates.getRTT() + " Last +ve BW: " + this.lastPositiveBW);
            }
            else {
                bandwidth = (Integer.parseInt(splitStats[0]) * 8) / completionTime; // filesize(Bytes)/totaltime(ms) = Kbps bandwidth
                this.lastPositiveBW = bandwidth;
            }
            // Cold start phase
            if (this.evictingQueueForHM.size() < this.evictingQueueForHM.maxSize()) {
                this.evictingQueueForHM.add(bandwidth);
//                System.out.println("Storing: " + this.evictingQueueForHM.size());

                // Normal Streaming Phase
            } else {
                if (this.estimates.isColdStartPhaseBW()) {
                    float bwHM = this.runningHMCalculator.getHM(); // calculates HM only once later just updates
                    this.estimates.setColdStartPhaseBW(false);
                    this.estimates.setBW(bwHM);
                } else {
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
    Estimates estimatesFastPath; // primary estimates
    Estimates estimatesSlowPath; // secondary estimates
    HashMap<Integer, Float> FGQPToQualityHashmap = new HashMap<>();
    HashMap<Integer, Float> BGQPToQualityHashmap = new HashMap<>();
    float lastStreamedSegQuality = 0;
    int numberOfShiftedTilesInLastSegment = 0;
    float qpFilesizeDelta = 1.2F; // delta times in filesize on changing QP
    float alpha1 = 1.0F; // 0.4F;
    float alpha2 = 1.0F; // 0.1F;
    float alpha3 = 1.0F; // 0.1F;
    float alpha4; // = 0.6F; // using max quality as max bitrate is used by prior works
    float alpha5 = 0.1F;
    float delta = 0.042F; // for 24 fps to treated as delta+tolerable limit rather than harsh 40ms of 25 fps
    float beta1 = 0.6F;
    float beta2 = 0.4F;
    float mu1 = 1.0F;
    float mu2 = 1.0F;
    int totalTiles = 16;
    float playtime = 0.160F; // 160ms for 4 frames at 25 fps
    public Scheduler(Estimates estimatesFastPath, Estimates estimatesSlowPath){
        // fastpath = primary path and slowpath = secondary path via helper
        this.estimatesFastPath = estimatesFastPath;
        this.estimatesSlowPath = estimatesSlowPath;

        int qp = 24; // qp 24, 27, 30, 33, 36, 39
        for(int q = 6; q >= 1; q--){
            BGQPToQualityHashmap.put(qp, q * 0.167F); // assigns weight of 1/6 to normalize 6 quality levels
            qp += 3;
        }

        qp = 24; // qp 24, 27, 30, 33
        for(int q = 4; q >= 1; q--){
            FGQPToQualityHashmap.put(qp, q * 0.25F); // assigns weight of 1/4 to normalize 4 quality levels
            qp += 3;
        }
    }

    public float getCompletionTime(int totalFileSize, float bandwidth, float rtt){
        // returns the completion of the given segment or tiles
        return (rtt/(1000*2)) + (totalFileSize/bandwidth);
    }

    public float[] getQualities(int numFGTiles, int FGTilesQP, int numBGTiles, int BGTilesQP, int numShiftedTiles, float FGCompletionTime, float BGCompletionTime, int lastRenderedBG){
        // lastRenderedBG is 0 for current seg, 1 for previous seg, and so on
        float qualityFG = (this.beta1*(numFGTiles + numShiftedTiles)*this.FGQPToQualityHashmap.get(FGTilesQP));
        float qualityBG;

        if ((BGCompletionTime - FGCompletionTime) <= (this.delta)) { // indicator function
            qualityBG = (this.beta2*((numBGTiles - numShiftedTiles)*this.BGQPToQualityHashmap.get(BGTilesQP)));
        } else {
//            qualityBG = (float) (this.beta2*(pow(2, (lastRenderedBG/2)) - 1));
//            qualityBG = (float) (this.beta2*(pow(2, (1/2)) - 1));
//            qualityBG = -(this.beta2*((numBGTiles - numShiftedTiles)*this.BGQPToQualityHashmap.get(BGTilesQP)));
            qualityBG = -this.alpha4;
//            System.out.println("Delayed render: FGQP = " + FGTilesQP + " BGQP = " + BGTilesQP + " FG Quality = " + qualityFG + " shifted tiles = " + numShiftedTiles + " FGTiles = " + numFGTiles + " BGTiles = " + numBGTiles + " completion time = " + (BGCompletionTime - FGCompletionTime));
        }
        return new float[] {qualityFG, qualityBG};
    }

    public float getInterSegQualitySwitch(float currentSegQuality){
        return abs(currentSegQuality - this.lastStreamedSegQuality);
    }

    public float getIntraSegQualitySwitch(float FGQuality, float BGQuality){
        return abs((this.mu1*FGQuality) - (this.mu2*BGQuality));
    }

    public float getStall(float FGCompletionTime, float BGCompletionTime, boolean areAllBGTilesShifted){
        if (areAllBGTilesShifted) { // indicator function to check if all BG tiles are shifted to FG
//            System.out.println("All BG tiles are shifted");
            return max(FGCompletionTime - this.playtime - this.delta, 0);
        }
        else {
            return max(max(FGCompletionTime, BGCompletionTime) - this.playtime - this.delta, 0);
        }
    }

    public int getShifts(int numberOfCurrentShiftedTiles){
        return Math.abs(this.numberOfShiftedTilesInLastSegment - numberOfCurrentShiftedTiles);
    }

    public float getQoE(int numFGTiles, int FGTilesQP, int numBGTiles, int BGTilesQP, int numShiftedTiles, float FGCompletionTime, float BGCompletionTime, int lastRenderedBG){
        float[] qualities = this.getQualities(numFGTiles, FGTilesQP, numBGTiles, BGTilesQP, numShiftedTiles, FGCompletionTime, BGCompletionTime, lastRenderedBG);
        float FGQuality = qualities[0];
        float BGQuality = qualities[1];
//        System.out.println("Components: " + (FGQuality + BGQuality) + " " + this.getStall(FGCompletionTime, BGCompletionTime) + " " + this.getInterSegQualitySwitch(FGQuality, BGQuality) + " " + this.getIntraSegQualitySwitch(FGQuality + BGQuality));
//        System.out.println("Components: " + (FGQuality + BGQuality) + " " + this.getStall(FGCompletionTime, BGCompletionTime) + " " + this.getIntraSegQualitySwitch(FGQuality, BGQuality) + " " + this.getInterSegQualitySwitch(FGQuality + BGQuality));
        return (this.alpha1 * (FGQuality + BGQuality)) - (this.alpha2 * this.getIntraSegQualitySwitch(FGQuality, BGQuality)) - (this.alpha3 * this.getInterSegQualitySwitch(FGQuality + BGQuality)) - (this.alpha4 * this.getStall(FGCompletionTime, BGCompletionTime, numShiftedTiles == numBGTiles)) - (this.alpha5 * this.getShifts(numShiftedTiles));
    }

    public String getOptimalSetting(float FGTilesCompletionTime, int numFGTiles, int numBGTiles, int eachFGTileSize, int eachBGTileSize, float FGPathRTT, float BGPathRTT, float FGPathBandwidth, float BGPathBandwidth) {
        float BGTilesCompletionTime = this.getCompletionTime(numBGTiles*eachBGTileSize, BGPathBandwidth, BGPathRTT);

        int fgQP = 24; int bgQP = 24;
        ArrayList<Float> qoes = new ArrayList<>();      // 4 (tiles shifts) x 6 (BG qp levels) x 4 (FG qp levels) = 96
        ArrayList<String> settings = new ArrayList<>(); // settings kept for tiles for qoes calculations. Format "numShiftedTiles_fgQP_bgQP".

        // Finds QoE for sending tiles without doing anything
        qoes.add(this.getQoE(numFGTiles, fgQP, numBGTiles, bgQP, 0, FGTilesCompletionTime, BGTilesCompletionTime, 0));
        settings.add("0_24_24");

        int newEachFGTileSize = eachFGTileSize; int newEachBGTileSize = eachBGTileSize;

        // Creating a hashmap to look for best filesize for a quality
        HashMap<Integer, Integer> FGQPFilesizeMap = new HashMap<>();
        HashMap<Integer, Integer> BGQPFilesizeMap = new HashMap<>();
        for (int i = 0; i < 4; i++){
            FGQPFilesizeMap.put(fgQP + (i * 3), newEachFGTileSize);
            newEachFGTileSize = (int) (newEachFGTileSize - (((this.qpFilesizeDelta - 1)/this.totalTiles) * newEachFGTileSize));
        }

        for (int i = 0; i < 6; i++){
            BGQPFilesizeMap.put(bgQP + (i * 3), newEachBGTileSize);
            newEachBGTileSize = (int) (newEachBGTileSize - (((this.qpFilesizeDelta - 1)/this.totalTiles) * newEachBGTileSize));
        }

//        System.out.println("Map: " + FGQPFilesizeMap);

        while (true){
            // Shifts tiles from BG to FG
            for(int o = 1; o < numBGTiles; o++){
//            for(int o = 1; o <= 6; o++)
                if ((o == numBGTiles) && (bgQP != fgQP)) {
                    break;
                }
//                else if (this.estimatesFastPath.getChannelStatus() == true && o == 1) { // primary is down
//                    FGTilesCompletionTime = this.getCompletionTime((numFGTiles + numBGTiles) * FGQPFilesizeMap.get(fgQP), BGPathBandwidth, BGPathRTT); // BGPath means secondary path
//                    BGTilesCompletionTime = 0; // this.getCompletionTime((numBGTiles - o) * BGQPFilesizeMap.get(bgQP), BGPathBandwidth, BGPathRTT);
//                    qoes.add(this.getQoE(numFGTiles, fgQP, numBGTiles, bgQP, o, FGTilesCompletionTime, BGTilesCompletionTime, 0));
//                    settings.add(o + "_" + fgQP + "_" + bgQP);
//                }
                else { // this makes the loop run only once when all tiles shifted to FG because no point in changing BG QP then as there aren't any tile in BG
                    FGTilesCompletionTime = this.getCompletionTime((numFGTiles * FGQPFilesizeMap.get(fgQP)) + (o * BGQPFilesizeMap.get(fgQP)), FGPathBandwidth, FGPathRTT);
                    BGTilesCompletionTime = this.getCompletionTime((numBGTiles - o) * BGQPFilesizeMap.get(bgQP), BGPathBandwidth, BGPathRTT);
                    qoes.add(this.getQoE(numFGTiles, fgQP, numBGTiles, bgQP, o, FGTilesCompletionTime, BGTilesCompletionTime, 0));
                    settings.add(o + "_" + fgQP + "_" + bgQP);
                }
            }
            if (bgQP < 39){     // last qp level
                bgQP += 3;      // QP increases in step size of 3
                continue;
            }
            if (fgQP < 33) {    // last qo level
                fgQP += 3;      // QP increases in step size of 3
                bgQP = fgQP;    // resets BG tiles QP
            }
            if (fgQP == 33 & bgQP == 39) {
                break;
            }
        }
        // Finding the index of max QoE
//        System.out.println("QoEs: " + qoes);
//        System.out.println("Settings: " + settings);
        float max = Collections.max(qoes);
        return settings.get(qoes.indexOf(max));
    }

    public String getSchedule(String infoString) {
        // infoString = "numTilesInFG_FGTilesFileSize_numTilesInBG_BGTilesFileSize"
        // outputString = "pathToSendFGTiles_numShiftedTiles_FGQP_BGQP"
        String[] splits = infoString.split("_");
        String numFGTiles = splits[0];
        int FGTilesFilesize = Integer.parseInt(splits[1]);
        String numBGTiles = splits[2]; String optimalSetting = null;
        int BGTilesFilesize = Integer.parseInt(splits[3]);
        float fastPathRTT = this.estimatesFastPath.getRTT();    //fast means primary and slow means secondary path
        float slowPathRTT = this.estimatesSlowPath.getRTT();
        float fastPathBW = this.estimatesFastPath.getBW();
        float slowPathBW = this.estimatesSlowPath.getBW();

        this.alpha4 = (Integer.parseInt(numFGTiles) * 0.25F) + (Integer.parseInt(numBGTiles) * 0.167F); // max quality
//        System.out.println("Alpha4: " + this.alpha4);

        // Chunk/Segment completion time (sct) = (RTT/2) + (SegmentFileSize/BandwidthEstimate)
        float sctPrimary = this.getCompletionTime(FGTilesFilesize, fastPathBW, fastPathRTT);    // for primary path
        float sctSecondary = this.getCompletionTime(FGTilesFilesize, slowPathBW, slowPathRTT);  // for secondary path
        int sizeOfEachFGTile = FGTilesFilesize / Integer.parseInt(numFGTiles);
        int sizeOfEachBGTile = BGTilesFilesize / Integer.parseInt(numBGTiles);
//        System.out.println("Filesize: " + FGTilesFilesize + " " + BGTilesFilesize + " " + sizeOfEachFGTile + " " + sizeOfEachBGTile);

        if (sctPrimary < sctSecondary) {
            // pick primary path to send FG tiles
            optimalSetting = "0_" + this.getOptimalSetting(sctPrimary, Integer.parseInt(numFGTiles), Integer.parseInt(numBGTiles), sizeOfEachFGTile, sizeOfEachBGTile, fastPathRTT, slowPathRTT, fastPathBW, slowPathBW);
        } else {
            // pick secondary path to send FG tiles
            optimalSetting = "1_" + this.getOptimalSetting(sctSecondary, Integer.parseInt(numFGTiles), Integer.parseInt(numBGTiles), sizeOfEachFGTile, sizeOfEachBGTile, slowPathRTT, fastPathRTT, slowPathBW, fastPathBW);
        }
        String[] split = optimalSetting.split("_");
//        System.out.println("Opt setting: " + optimalSetting);

        float qualityFG = this.beta1 * (Integer.parseInt(numFGTiles) + Integer.parseInt(split[1])) * this.FGQPToQualityHashmap.get(Integer.parseInt(split[2]));
        float qualityBG = this.beta2 * (Integer.parseInt(numBGTiles) - Integer.parseInt(split[1])) * this.BGQPToQualityHashmap.get(Integer.parseInt(split[3]));
        this.lastStreamedSegQuality = qualityFG + qualityBG;
        this.numberOfShiftedTilesInLastSegment = Integer.parseInt(split[1]);
        return optimalSetting;
    }
}
