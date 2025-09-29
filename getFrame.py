###################################################################
# Script to extract frames using FFmpeg from recorded videos.
# Video file should sollow naming convention as:
#    packetlevel_bflow_video5.mkv or packetlevel_bflow_video5.mkv
###################################################################

import subprocess as sp
from pathlib import Path

root = "./"
# trace = "Live/"
trace = "bus/"

# # For previous oline class applicaton
# # videos = ["Video1", "Video2", "Video3", "Video4", "Video5"]
# # videos = ["Video2", "Video3", "Video4", "Video5"]
# videos = ["Video5"]
# # videos = ["live"]
# schedulers = ["PSP1", "PSP2", "Pbflow", "PminRTT", "Pmusher", "Tbflow", "TminRTT", "Tmusher", "Tours"]
# for video in videos:
#     for scheduler in schedulers:
#         Path(f"{root+trace+video}/{scheduler}").mkdir(parents=True, exist_ok=True)
#         if scheduler[0] == "T":
#             sp.run(f"ffmpeg -hide_banner -i {root+trace+video}/tilelevel_{scheduler[1:]}_{video.lower()}.mkv -r 10 {root+trace+video}/{scheduler}/fr_%04d.png".split(" "))
#             # sp.run(f"ffmpeg -hide_banner -i {root+trace+video}/tilelevel_{scheduler[1:-1]}_{video.lower()}_walk_latest.mkv -r 10 {root+trace+video}/{scheduler}/fr_%04d.png".split(" "))            

#         else:
#             sp.run(f"ffmpeg -i {root+trace+video}/packetlevel_{scheduler[1:]}_{video.lower()}.mkv -r 10 {root+trace+video}/{scheduler}/fr_%04d.png".split(" "))
            
# # For fault_tolerance
# root = "Videos/"
# trace = "fault_tolerance"
# video = "Video4"
# for scheduler in schedulers:
#     Path(f"{root+trace}/{scheduler}").mkdir(parents=True, exist_ok=True)
#     sp.run(f"ffmpeg -hide_banner -i {root+trace}/tilelevel_{scheduler[1:]}_{video.lower()}.mkv -r 10 {root+trace}/{scheduler}/fr_%04d.png".split(" "))

# For new applications
videos = ["Badminton", "Tennis", "USNews"]
schedulers = ["minrtt", "compact"]
for video in videos:
    for scheduler in schedulers:
        Path(f"{root+trace+video}/{scheduler}").mkdir(parents=True, exist_ok=True)
        # For new applications
        sp.run(f"ffmpeg -hide_banner -i {root+trace+video}/Lag_sctp_{scheduler}_{video.lower()}_{trace[:-1]}.mp4 {root+trace+video}/{scheduler}/fr_%04d.png".split(" "))
