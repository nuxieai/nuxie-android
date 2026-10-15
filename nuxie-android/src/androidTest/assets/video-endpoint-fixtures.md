# Android video endpoint test media

These files are Android-only test media for the endpoint seek limit in
`AndroidVideoDecoder`. They are not part of the shared `fixtures/` export.

`endpoint-timecoded.mp4` has the same shape as the shared
`fixtures/video/endpoint-low-fps.mp4`: 64x32, 10 frames per second, 20 frames,
red from 0 to 0.9 seconds, blue from 1.0 to 1.9 seconds, sync frames at 0 and
1 second, and a silent AAC track that ends with the video at 2 seconds. Each
frame also carries its own number in the green channel (frame number times 12),
so a test reads which decoded sample it is looking at from the image itself
instead of from the player's position. The final sample, at 1.9 seconds, has
green 228.

`endpoint-audio-tail.mp4` is the same video with its silent audio running to
2.25 seconds, past the last image. A seek into that tail must keep the
requested time.

`endpoint-last-keyframe.mp4` is the time-coded video with an extra sync frame
on its last frame (1.9 seconds), so reading from the last sync frame finds only
that one sample.

`endpoint-unshifted-audio.mp4` has the same frames without B-frames, muxed with
an AAC stream copied from ADTS, so the file has no edit list and the audio's
1,024-sample encoder delay stays in its sample times: the last AAC sample
starts at 2.0053 seconds and the audio ends at 2.0267 seconds. It is what a
platform that ignores the edit list reports for an ordinary file whose audio
ends with the video, and it must keep the seek limit.

Generated with ffmpeg 9.0.1 (libx264, aac):

```sh
ffmpeg -f lavfi -i "color=c=black:s=64x32:r=10:d=2,format=rgb24,geq=r='if(lt(N,10),255,0)':g='N*12':b='if(lt(N,10),0,255)'" \
  -f lavfi -i "anullsrc=r=48000:cl=mono" -t 2 -map 0:v -map 1:a \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 4 -g 10 -keyint_min 10 -sc_threshold 0 -bf 3 \
  -color_primaries smpte170m -color_trc smpte170m -colorspace smpte170m -color_range tv \
  -c:a aac -b:a 64k -movflags +faststart endpoint-timecoded.mp4
ffmpeg -i endpoint-timecoded.mp4 -f lavfi -i "anullsrc=r=48000:cl=mono" -map 0:v -map 1:a \
  -c:v copy -c:a aac -b:a 64k -t 2.25 -movflags +faststart endpoint-audio-tail.mp4
ffmpeg -f lavfi -i "color=c=black:s=64x32:r=10:d=2,format=rgb24,geq=r='if(lt(N,10),255,0)':g='N*12':b='if(lt(N,10),0,255)'" \
  -f lavfi -i "anullsrc=r=48000:cl=mono" -t 2 -map 0:v -map 1:a \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 4 -g 10 -keyint_min 1 -sc_threshold 0 -bf 3 \
  -force_key_frames "expr:eq(n,0)+eq(n,10)+eq(n,19)" \
  -color_primaries smpte170m -color_trc smpte170m -colorspace smpte170m -color_range tv \
  -c:a aac -b:a 64k -movflags +faststart endpoint-last-keyframe.mp4
ffmpeg -f lavfi -i "color=c=black:s=64x32:r=10:d=2,format=rgb24,geq=r='if(lt(N,10),255,0)':g='N*12':b='if(lt(N,10),0,255)'" \
  -t 2 -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 4 -g 10 -keyint_min 10 -sc_threshold 0 -bf 0 \
  -color_primaries smpte170m -color_trc smpte170m -colorspace smpte170m -color_range tv video.mp4
ffmpeg -f lavfi -i "anullsrc=r=48000:cl=mono" -t 2 -c:a aac -b:a 64k -f adts audio.aac
ffmpeg -i video.mp4 -i audio.aac -map 0:v -map 1:a -c copy -movflags +faststart endpoint-unshifted-audio.mp4
```

Encoders differ between versions, so a regeneration need not match these bytes;
it must keep the frame times, colors and track lengths above.

| File | SHA-256 |
| --- | --- |
| video-endpoint/endpoint-timecoded.mp4 | 52b9f39464b78db7d84df1661ce2e918d0d4f6e6dd84d6a603344aeb5364004e |
| video-endpoint/endpoint-audio-tail.mp4 | 58cbc47b4844a26b3340a9b62c5a13559e843f660d4a4196cac53f426dbe3499 |
| video-endpoint/endpoint-last-keyframe.mp4 | 67a8f219f11d3305ac97a6b319869589672ef11f646ed9d63c7ed8a847e43f27 |
| video-endpoint/endpoint-unshifted-audio.mp4 | c4feb4cfb4e95e19b53670db1aa7bf900c5a184e29e67c2d80402c184cb8714b |
