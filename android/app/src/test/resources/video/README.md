# Generated MPEG-4 timing fixture

`mpeg4-bframes.avi` contains only FFmpeg's generated `testsrc2` pattern, no third-party video.

```sh
ffmpeg -f lavfi -i testsrc2=size=64x48:rate=24000/1001 -frames:v 60 \
  -c:v mpeg4 -bf 1 -g 12 -q:v 4 -an mpeg4-bframes.avi
```

`mpeg4-bframes.json` stores each compressed packet's SHA-256 and its expected presentation
time in microseconds. The ordering oracle is an independent FFmpeg MPEG-4 decoder via
PyAV: pass each demuxed packet with `pts=packet_index`, `dts=None`, `time_base=1`;
collect the returned frame PTS values, including decoder drain. This identifies the input
packet for each displayed frame. Assign that packet `display_index * 1001 * 1000000 // 24000`.
Every packet appears exactly once. The fixture crosses second boundaries and several GOPs.

The regression uses the real Media3 AVI extractor and compares its complete compressed
output and timestamps to this oracle. Decoder pixel output is not simulated by Robolectric.
