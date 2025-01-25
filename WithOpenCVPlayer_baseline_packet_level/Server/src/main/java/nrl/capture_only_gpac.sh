kvazaar -i $1 --input-res 1280x720 --input-fps $2 --qp $4 --tiles $3 --slices tiles --mv-constraint frametilemargin --no-psnr --no-info --no-bipred -o - | gpac -i - tilesplit @ tileagg @ -o $5
