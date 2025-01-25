##### Older one
#ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size $2 -c:v rawvideo -i :0.0+0,0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p - | kvazaar -i - --input-res $2 --input-fps $1 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --preset superfast -o - | gpac -i - tilesplit @ tileagg @ -o $5
## ffmpeg -hide_banner -f v4l2 -framerate 30 -video_size 12890x720 -c:v rawvideo -pix_fmt yuv420p -i /dev/video0 -f rawvideo -r 30 -pix_fmt yuv420p - | kvazaar -i - --input-res 1280x720 --input-fps 30 --tiles 4x4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --preset superfast -o - | gpac -i - tilesplit @ -o out.mp4

##### New one same as Multipath_With_Our_Scheduler
# FFmpeg only
ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size 1280x720 -c:v rawvideo -i :0.0+0,0 -vframes $2 -f rawvideo -r $1 -pix_fmt yuv420p $3
