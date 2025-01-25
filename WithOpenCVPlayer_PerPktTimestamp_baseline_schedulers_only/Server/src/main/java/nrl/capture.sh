# Fg and Bg simulataneously
# Working
# Only Kvazaar
#ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size $2 -c:v rawvideo -i :0.0+0,0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p - | kvazaar -i - --input-res $2 --input-fps $1 --qp 24 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info -o $5
#ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size $2 -c:v rawvideo -i :0.0+0,0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p -vf "settb=AVTB,setpts='trunc(PTS/1K)*1K+st(1,trunc(RTCTIME/1K))-1K*trunc(ld(1)/1K)',drawtext=%03d'fontfile=/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf:fontsize=40:fontcolor=black:box=1:boxcolor=white:text=%{localtime\:%s}%{eif\:1M*t-1K*trunc(t*1K)\:d\:3}'" - | kvazaar -i - --input-res $2 --input-fps $1 --tiles $4 --qp 24 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o $5
#ffmpeg -hide_banner -loglevel quiet -f v4l2 -framerate $1 -video_size $2 -c:v rawvideo -i /dev/video0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p - | kvazaar -i - --input-res $2 --input-fps $1 --qp 24 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o $5

# FFmpeg only
ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size 1280x720 -c:v rawvideo -i :0.0+0,0 -vframes $2 -f rawvideo -r $1 -pix_fmt yuv420p $3
