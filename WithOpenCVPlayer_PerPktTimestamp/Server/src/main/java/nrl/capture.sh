# Working
# Single encoding for all tiles
#ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size $2 -c:v rawvideo -i :0.0+0,0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p - | kvazaar -i - --input-res $2 --input-fps $1 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o $5

# With separate FG and BG encoding
#ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size $2 -c:v rawvideo -i :0.0+0,0 -vframes $3 -f rawvideo -r $1 -pix_fmt yuv420p - | tee >(kvazaar -i - --input-res $2 --input-fps $1 --qp $5 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o $7) | kvazaar -i - --input-res $2 --input-fps $1 --qp $6 --tiles $4 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o $8

# FFmpeg only
ffmpeg -hide_banner -loglevel quiet -f x11grab -framerate $1 -video_size 1280x720 -c:v rawvideo -i :0.0+0,0 -vframes $2 -f rawvideo -r $1 -pix_fmt yuv420p $3
