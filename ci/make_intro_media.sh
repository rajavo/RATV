#!/bin/bash
# Three episodes of one series sharing a 40 s opening (a melody with a beat), with their own talk
# around it: the opening starts at 20 s, 15 s and 8 s; the last 20 s of each are the same end credits
# (another melody). Each file is encoded on its own, as a server's are.
set -e
out=${1:-ci/media}
mkdir -p "$out"
tmp=$(mktemp -d)
note='330*pow(2,(floor(mod(t*3.33,8))*2+floor(mod(t*1.11,3)))/12)'
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "aevalsrc='0.25*sin(2*PI*t*($note))*(1-0.6*mod(t*3.33,1))+0.12*sin(4*PI*t*($note))*(1-0.6*mod(t*3.33,1))+0.18*sin(2*PI*t*(110*pow(2,floor(mod(t*0.55,4))*5/12)))+0.15*(random(0)*2-1)*exp(-30*mod(t,0.5))':s=48000:d=40" -ac 2 "$tmp/theme.wav"
ending='220*pow(2,(floor(mod(t*2.5,6))*3)/12)'
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "aevalsrc='0.3*sin(2*PI*t*($ending))*(1-0.5*mod(t*2.5,1))+0.15*sin(2*PI*t*(82*pow(2,floor(mod(t*0.4,3))*7/12)))+0.1*(random(0)*2-1)*exp(-20*mod(t*2,1))':s=48000:d=20" -ac 2 "$tmp/credits.wav"
talk='bandpass=f=900:width_type=h:w=1400,volume=0.6+0.4*sin(2*PI*t*RATE):eval=frame,aformat=sample_rates=48000:channel_layouts=stereo'
for n in 1 2 3; do
  case $n in 1) pre=20 ;; 2) pre=15 ;; 3) pre=8 ;; esac
  post=$((100 - pre - 40 - 20))
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "anoisesrc=color=pink:seed=$((n * 10 + 1)):sample_rate=48000:duration=$pre" \
    -i "$tmp/theme.wav" \
    -f lavfi -i "anoisesrc=color=pink:seed=$((n * 10 + 2)):sample_rate=48000:duration=$post" \
    -i "$tmp/credits.wav" \
    -filter_complex "[0:a]${talk/RATE/3.$n}[a];[1:a]aformat=sample_rates=48000:channel_layouts=stereo[b];[2:a]${talk/RATE/4.$n}[c];[3:a]aformat=sample_rates=48000:channel_layouts=stereo[d];[a][b][c][d]concat=n=4:v=0:a=1[s]" \
    -map "[s]" "$tmp/ep$n.wav"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=640x360:rate=25 -i "$tmp/ep$n.wav" -t 100 \
    -map 0:v -map 1:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 400k -g 50 -c:a aac -b:a 96k -movflags +faststart "$out/intro$n.mp4"
done
rm -rf "$tmp"
ls -la "$out"/intro*.mp4
