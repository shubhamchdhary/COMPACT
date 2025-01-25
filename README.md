# COMPACT
## Introduction
This repository contains codes/artifacts for the paper "COMPACT: Content-aware Multipath Live Video Streaming for Online Classes using Video Tiles", published at ACM Multimedia Systems Conference (MMSys) 2025. COMPACT is a system for live streaming online classes that utilizes tiles to seggregate the video content into foreground (FG) and background (BG). Then a content-aware scheduler streams the FG and BG tiles over differnet paths available. Please write to shubhamch@iiitd.ac.in for any queries/doubts.

## Getting Started
### 1) Directory Structure
```
└── QPs                                                         : Contains text files with FG and BG QPs of COMPACT for each video of every trace
|
└── WithOpenCVPlayer_baseline_packet_level                      : Contains source code for packet level baseline schedulers
|
└── WithOpenCVPlayer_PerPktTimestamp                            : Contains source code for COMPACT
|
└── WithOpenCVPlayer_PerPktTimestamp_baseline_schedulers_only   : Contains source code for tile level baseline schedulers
|              
└── generateResults.ipynb                                       : Jupyter notebook file to generate all the plots
|
└── new_clock_serv.py                                           : Code to run the live digital clock on screen to clculate E2E lag
|
└── clockTest_edited.ipynb                                      : Jupyter notebook to extract text from the recorded video of live clocks

```

### 2) Dependencies
The codes can only be run Linux (tested on >=Ubuntu 20.04). The codebase uses FFmepg, GPAC, and Kvazaar to encode and stream tiled videos. FFmpeg can be directly installed using `sudo apt install ffmpeg` command. However, [Kvazaar](https://github.com/ultravideo/kvazaar) and [GPAC](https://github.com/gpac/gpac/wiki/Build-Introduction) requires them to build. Follow the build instructions in their respective repositories. For GPAC, go with a full GPAC build, not the minimal ones. The clock and the Jupyter notebook uses Python 3.8.10. All the python libraries can be installed using `pip3 install -r requirements.txt`. The entire code of COMPACT and the baselines is in Java. Use IntelliJ to run the codes. Install OpenCV 4.7.0 for Java in IntelliJ on both server and primary devices.

### 3) Downloading Dataset 
For quick validation of the reported results we have provided all the necessary traces, scripts, and pre-processed files at [Zenodo](""). Simply download and extract it in the current path.

### 4) Reproducing Results
To quickly reproduce the results reported in the paper, the necessary text and pickle files are place in the `Results` downloaded as directed above. Simply place the extracted `Results` directory at current path. The utilize the `generateResults.ipynb` notebook file to generate the plots. Note this notebook file must be run locally, not on Google Colab, as it parses the files inside `Results` to generate results. The `generateResults.ipynb` file can be run inside VS Code IDE or Jupyter Notebook.

## How to run COMPACT and Baselines?
- **For multi-Path with only tile-level baseline schedulers:**
Use the code available in `WithOpenCVPlayer_PerPktTimestamp_baseline_schedulers_only` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For multi-Path with only packet-level baseline schedulers:**
Use the code available in `WithOpenCVPlayer_baseline_packet_level` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For COMPACT:**
Use the code available in `WithOpenCVPlayer_PerPktTimestamp` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

*NOTE: To simulate a network using Mahimahi traces. Use a different PC, start an mm-link shell with appropriate trace file and run `socat UDP4-LISTEN:8001,fork UDP4:server-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:server-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:server-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:server-ip:6002` inside the shell. Run `socat UDP4-LISTEN:8001,fork UDP4:link-shell-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:link-shell-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:link-shell-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:link-shell-ip:6002` in another terminal outside the Mahimahi link shell. This will redirect packets from the outer world to the shell, add network characteristics, and send back again outside. This is for primary channel. For secondary, run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002 & socat -b20000 SCTP4:server-ip:6003 SCTP4-LISTEN:7003` inside and `socat UDP4-LISTEN:8002,fork UDP4:link-shell-ip:8002 & socat -b20000 SCTP4:link-shell-ip:7003 SCTP4-LISTEN:7003` outside.*

We use clockTest_edited.ipynb to detect clock using OCR.