# Working Codes of NATIVE

## Dependencies
The codebase uses FFmepg, GPAC, and Kvazaar to encode and stream tiled videos. FFmpeg can be directly installed using `sudo apt install ffmpeg` command. However, [Kvazaar](https://github.com/ultravideo/kvazaar) and [GPAC](https://github.com/gpac/gpac/wiki/Build-Introduction) requires them to build. Follow the build instructions in their respective repositories. For GPAC, go with a full GPAC build, not the minimal ones.

## How to run?
- **For Single Path:**
Use the code available in `SinglePath` folder. First, run the server using `java Main tmp/ 0` (0 to capture and stream screen). Then, at the client, run `java Main tmp/ server-ip server-port` (specify IP and Port). `clean.sh` is used to clear out old files before starting a new session. `capture.sh` captures the screen, and encodes into tiled HEVC  videos. 


- **For Multi-Path Without Scheduler:**
Use the code available in `MultiPathTiledWithoutSchedular` folder. First, run the server using `java Main tmp/ 0`. Then, at the helper, run SOCAT command `socat -b<buffSize> SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port`. Finally, at the client, run `java Main tmp/ 0`. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For Multi-Path With Scheduler:**
Use the code available in `MultiPath_WithSchedular/WithOpenCVPlayer_PerPktTimestamp` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b<buffSize> SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For Multi-Path With Scheduler/With only tile-level baseline schedulers:**
Use the code available in `MultiPath_WithSchedular/WithOpenCVPlayer_PerPktTimestamp_baseline_schedulers_only` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b<buffSize> SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

- **For Multi-Path With Scheduler/With only packet-level baseline schedulers:**
Use the code available in `MultiPath_WithSchedular/WithOpenCVPlayer_baseline_packet_level` folder. First, run the server in IntelliJ. Then, at the helper, run SOCAT command `socat -b<buffSize> SCTP4:server-ip:serverToHelper-port SCTP4-LISTEN:clientToHelper-port` for the video channel and in another terminal run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002` for the control channel for RTT Estimation. Finally, run the client in IntelliJ at the user side. *Note you need to change the server and helper IP and Port in the client's `Main.java` code before executing.*

*NOTE: To simulate a network using Mahimahi traces. Use a different PC, start an mm-link shell and run `socat UDP4-LISTEN:8001,fork UDP4:server-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:server-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:server-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:server-ip:6002` inside the shell. Run `socat UDP4-LISTEN:8001,fork UDP4:link-shell-ip:8001 & socat UDP4-LISTEN:8003,fork UDP4:link-shell-ip:8003 & socat UDP4-LISTEN:8004,fork UDP4:link-shell-ip:8004 & socat -b20000 SCTP4-LISTEN:6002 SCTP4:link-shell-ip:6002` in another terminal outside the Mahimahi link shell. This will redirect packets from the outer world to the shell, add network characteristics, and send back again outside. This is for primary channel. For secondary, run `socat UDP4-LISTEN:8002,fork UDP4:server-ip:8002 & socat -b20000 SCTP4:server-ip:6003 SCTP4-LISTEN:7003` inside and `socat UDP4-LISTEN:8002,fork UDP4:link-shell-ip:8002 & socat -b20000 SCTP4:link-shell-ip:7003 SCTP4-LISTEN:7003` outside.*

We use clockTest_edited.ipynb to detect clock using OCR.

Codes to get results and the result files are available at https://drive.google.com/drive/folders/1xAK4vmW9S5Q42lU67VXtnba_DnWpRP6t?usp=drive_link
