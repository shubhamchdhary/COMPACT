# Fg and Bg simulataneously
# Working
#cat $1 | tee >(gpac -i - tilesplit:tiledrop=$2 @ tileagg @ -o $4) | gpac -i - tilesplit:tiledrop=$3 @ tileagg @ -o $5

# For separate FG and BG
cat $1 | tee >(kvazaar -i - --input-res 1280x720 --input-fps $2 --qp $4 --tiles $3 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o - | gpac -i - tilesplit @ tileagg @ -o $5) | (kvazaar -i - --input-res 1280x720 --input-fps $2 --qp $6 --tiles $3 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o -  | gpac -i - tilesplit:tiledrop=$7 @ tileagg @ -o $8)
