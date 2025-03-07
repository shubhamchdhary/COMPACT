# COMPACT
## Introduction
This repository contains codes/artifacts for the paper "COMPACT: Content-aware Multipath Live Video Streaming for Online Classes using Video Tiles", published at ACM Multimedia Systems Conference (MMSys) 2025. COMPACT is a system for live streaming online classes that utilizes tiles to seggregate the video content into foreground (FG) and background (BG). Then a content-aware scheduler streams the FG and BG tiles over differnet paths available. Please write to shubhamch@iiitd.ac.in for any queries/doubts.

## Getting Started
### 1) Directory Structure
```
└── QPs                                                         : Contains text files with FG and BG QPs of COMPACT for each video of every trace. These are fed to each schedulers to keep the quality levels same for fairness.
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
|
└── inside_mahimahi_client.sh                                   : Script to run inside Mahimahi link shell for primary path trace
|
└── outside_mahimahi_client.sh                                  : Script to run outside Mahimahi link shell for primary path trace
|
└── inside_mahimahi_helper.sh                                   : Script to run inside Mahimahi link shell for helper path trace
|
└── outside_mahimahi_helper.sh                                  : Script to run outside Mahimahi link shell for helper path trace
|
└── outside_mahimahi_helper_live.sh                             : Script to run outside Mahimahi link shell for helper path trace in live setup
|
└── Results                                                     : Available once the dataset is downloaded and extracted. Contains pre-processed files for quick result validation.

```

### 2) Dependencies
The codes can only be run Linux (tested on >=Ubuntu 20.04). The codebase uses FFmepg, GPAC, and Kvazaar to encode and stream tiled videos. FFmpeg can be directly installed using `sudo apt install ffmpeg` command. However, [Kvazaar](https://github.com/ultravideo/kvazaar) and [GPAC](https://github.com/gpac/gpac/wiki/Build-Introduction) requires them to build. Follow the build instructions in their respective repositories. For GPAC, go with a full GPAC build, not the minimal ones. The clock and the Jupyter notebook uses Python 3.8.10. All the python libraries can be installed using `pip3 install -r requirements.txt`. The entire code of COMPACT and the baselines is in Java. Use IntelliJ to run the codes. Install OpenCV 4.7.0 for Java in IntelliJ on both server and primary devices. To replay traces, we rely on [Mahimahi](http://mahimahi.mit.edu/).

### 3) Downloading Dataset 
For quick validation of the reported results, we have provided all the necessary traces, scripts, and pre-processed files at [Zenodo](https://doi.org/10.5281/zenodo.14740088). Simply download and extract it in the current path. Links to the four videos used on the paper for experiments are:
- [Video 1](https://www.youtube.com/watch?v=wiNXzydta4c)
- [Video 2](https://www.youtube.com/watch?v=XtlwSmJfUs4)
- [Video 3](https://www.youtube.com/watch?v=RPbtzWgzD9M)
- [Video 4](https://www.youtube.com/watch?v=O--rkQNKqls)

### 4) Reproducing Results
To quickly reproduce the results reported in the paper, the necessary text and pickle files are placed inside `Results` folder downloaded as directed above. Simply place the extracted `Results` directory at the current path. Then utilize the `generateResults.ipynb` notebook file to generate the plots. Note this notebook file must be run locally, not on Google Colab, as it parses the files inside `Results` to generate results. The `generateResults.ipynb` file can be run inside VS Code IDE or Jupyter Notebook.

## How to run COMPACT and Baselines?
- **For multi-Path with only tile-level baseline schedulers:**
Use the code available in `WithOpenCVPlayer_PerPktTimestamp_baseline_schedulers_only` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For multi-Path with only packet-level baseline schedulers:**
Use the code available in `WithOpenCVPlayer_baseline_packet_level` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For COMPACT:**
Use the code available in `WithOpenCVPlayer_PerPktTimestamp` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b2500 SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

*NOTE: To simulate a network using Mahimahi traces. Use a different PC, start an mm-link shell with appropriate trace file and run `socat UDP4-LISTEN:8001,fork UDP4:server-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:server-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:server-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:server-ip:6002` inside the shell. Run `socat UDP4-LISTEN:8001,fork UDP4:link-shell-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:link-shell-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:link-shell-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:link-shell-ip:6002` in another terminal outside the Mahimahi link shell. This will redirect packets from the outer world to the shell, add network characteristics, and send back again outside. This is for primary channel. For secondary, run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002 & socat -b20000 SCTP4:server-ip:6003 SCTP4-LISTEN:7003` inside and `socat UDP4-LISTEN:8002,fork UDP4:link-shell-ip:8002 & socat -b20000 SCTP4:link-shell-ip:7003 SCTP4-LISTEN:7003` outside.*

We use clockTest_edited.ipynb to detect clock using OCR.

## Demo Video
<!DOCTYPE html>
<html>
<body>
<div><iframe allowfullscreen="allowfullscreen" src="https://drive.google.com/file/d/1utVCg4F4r_Xn3sbA2EZFZu03TcXxu44r/preview" width="640" height="480" allow="autoplay"></iframe></div>
</body>
</html>


## Citing TileClipper
```
@inproceedings {compact,
author = {Shubham Chaudhary and Navneet Mishra and Keshav Gambhir and Tanmay Rajore and Arani Bhattacharya and Mukulika Maity},
title = {COMPACT: Content-aware Multipath Live Video Streaming for Online Classes using Video Tiles},
booktitle = {2025 ACM Multimedia Systems Conference (ACM MMSys'25)},
year = {2025},
isbn = {979-8-4007-1467-2/25/03},
address = {Stellenbosch, South Africa},
doi = {https://doi.org/10.1145/3712676.3714451}
}
```
