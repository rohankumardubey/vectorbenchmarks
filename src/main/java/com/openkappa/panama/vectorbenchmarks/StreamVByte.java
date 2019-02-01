package com.openkappa.panama.vectorbenchmarks;


import jdk.incubator.vector.ByteVector;
import org.openjdk.jmh.annotations.*;

import static com.openkappa.panama.vectorbenchmarks.Util.B128;
import static com.openkappa.panama.vectorbenchmarks.Util.I128;
import static com.openkappa.panama.vectorbenchmarks.Util.S128;

@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsPrepend = {"--add-modules=jdk.incubator.vector",
        "-XX:TypeProfileLevel=111", "-XX:-TieredCompilation", "-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0"})
public class StreamVByte {

  static long scalarEncode(int[] in,
                           byte[] keys,
                           byte[] data,
                           int index,
                           int keyIndex,
                           int count) {
    if (count > 0) {

      byte shift = 0; // cycles 0, 2, 4, 6, 0, 2, 4, 6, ...
      byte key = 0;
      for (int c = 0; c < count; c++) {
        if (shift == 8) {
          shift = 0;
          keys[keyIndex++] = key;
          key = 0;
        }
        int val = in[c];
        byte code;
        if (val < (1 << 8)) { // 1 byte
          data[index++] = (byte) (val);
          code = 0;
        } else if (val < (1 << 16)) { // 2 bytes
          data[index++] = (byte) (val & 0xFF);   // assumes little endian
          data[index++] = (byte) ((val >>> 8) & 0xFF);
          code = 1;
        } else if (val < (1 << 24)) { // 3 bytes
          data[index++] = (byte) (val & 0xFF);   // assumes little endian
          data[index++] = (byte) ((val >>> 8) & 0xFF);
          data[index++] = (byte) ((val >>> 16) & 0xFF);
          code = 2;
        } else { // 4 bytes
          data[index++] = (byte) (val & 0xFF);   // assumes little endian
          data[index++] = (byte) ((val >>> 8) & 0xFF);
          data[index++] = (byte) ((val >>> 16) & 0xFF);
          data[index++] = (byte) ((val >>> 24) & 0xFF);
          code = 3;
        }
        key |= code << shift;
        shift += 2;
      }
      keys[keyIndex] = key;  // write last key (no increment needed)
    }
    return index | ((long) keyIndex << 32); // position of first unused data byte packed with position of first unused key
  }


  static int streamVByteEncode4(ByteVector in,
                                 byte[] data, int di,
                                 byte[] keys, int ki) {
    var ones = I128.broadcast(0x01010101).rebracket(B128);
    var gatherBits = I128.broadcast(0x08040102).rebracket(S128);
    var codeTable = I128.scalars(0x03030303, 0x03030303, 0x03030303, 0x02020100).rebracket(B128);
    var gatherBytes = I128.scalars(0, 0, 0x0D090501, 0x0D090501).rebracket(B128);
    var aggregators = I128.scalars(0, 0, 0x01010101, 0x10400104).rebracket(S128);

    // in general wrong because there are no unsigned types, but correct some of the time
    var m1 = (ByteVector) in.min(ones)
               .rebracket(S128)
               .add(gatherBits)
               .rebracket(B128)
               .rearrange(codeTable.toShuffle())
               .rearrange(gatherBytes.toShuffle())
               .rebracket(S128)
               .add(aggregators)
               .rebracket(B128);

    int code = m1.get(1) & 0xFF;
    int length = LENGTH_TABLE[code];
    var shuffle = B128.fromArray(ENCODING_SHUFFLE_TABLE, code * 16).toShuffle();
    in.rearrange(shuffle).intoArray(data, di);
    keys[ki] = (byte)code;
    return length;
  }

  static int streamVByteEncodeQuad(int[] in, int ii, byte[] out, int oi, byte[] keys, int ki) {
    return streamVByteEncode4((ByteVector) I128.fromArray(in, ii).rebracket(B128), out, oi, keys, ki);
  }


  long streamVByteEncode0124(int[] in, int i, int count, byte[] out, int o) {
    byte[] keys = out;
    int keyLen = (count + 3) >>> 4;  // 2-bits rounded to full byte
    byte[] data = out;

    int countQuads = count >>> 4;
    count -= 4 * countQuads;
    int ki = o;
    int di = 0 + keyLen;
    for (int c = 0; c < countQuads; c++) {
      ki += streamVByteEncodeQuad(in, i, data, o + keyLen,  keys, o);
      ki++;
      i += 4;
    }
    return scalarEncode(in, keys, data, i, ki, count) - di;
  }



    // using 0,1,2,4 bytes per value
    static byte[] LENGTH_TABLE = new byte[] {
      0,  1,  2,  4,  1,  2,  3,  5,  2,  3,  4,  6,  4,  5,  6,  8,
      1,  2,  3,  5,  2,  3,  4,  6,  3,  4,  5,  7,  5,  6,  7,  9,
      2,  3,  4,  6,  3,  4,  5,  7,  4,  5,  6,  8,  6,  7,  8, 10,
      4,  5,  6,  8,  5,  6,  7,  9,  6,  7,  8, 10,  8,  9, 10, 12,
      1,  2,  3,  5,  2,  3,  4,  6,  3,  4,  5,  7,  5,  6,  7,  9,
      2,  3,  4,  6,  3,  4,  5,  7,  4,  5,  6,  8,  6,  7,  8, 10,
      3,  4,  5,  7,  4,  5,  6,  8,  5,  6,  7,  9,  7,  8,  9, 11,
      5,  6,  7,  9,  6,  7,  8, 10,  7,  8,  9, 11,  9, 10, 11, 13,
      2,  3,  4,  6,  3,  4,  5,  7,  4,  5,  6,  8,  6,  7,  8, 10,
      3,  4,  5,  7,  4,  5,  6,  8,  5,  6,  7,  9,  7,  8,  9, 11,
      4,  5,  6,  8,  5,  6,  7,  9,  6,  7,  8, 10,  8,  9, 10, 12,
      6,  7,  8, 10,  7,  8,  9, 11,  8,  9, 10, 12, 10, 11, 12, 14,
      4,  5,  6,  8,  5,  6,  7,  9,  6,  7,  8, 10,  8,  9, 10, 12,
      5,  6,  7,  9,  6,  7,  8, 10,  7,  8,  9, 11,  9, 10, 11, 13,
      6,  7,  8, 10,  7,  8,  9, 11,  8,  9, 10, 12, 10, 11, 12, 14,
      8,  9, 10, 12,  9, 10, 11, 13, 10, 11, 12, 14, 12, 13, 14, 16
    };

// encoding:
    static byte[] ENCODING_SHUFFLE_TABLE = new byte[] {
       -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0000
        0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1000
        0,  1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2000
        0,  1,  2,  3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3000
        4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0100
        0,  4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1100
        0,  1,  4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2100
        0,  1,  2,  3,  4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3100
        4,  5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0200
        0,  4,  5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1200
        0,  1,  4,  5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2200
        0,  1,  2,  3,  4,  5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3200
        4,  5,  6,  7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0300
        0,  4,  5,  6,  7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1300
        0,  1,  4,  5,  6,  7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2300
        0,  1,  2,  3,  4,  5,  6,  7, -1, -1, -1, -1, -1, -1, -1, -1,    // 3300
        8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0010
        0,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1010
        0,  1,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2010
        0,  1,  2,  3,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3010
        4,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0110
        0,  4,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1110
        0,  1,  4,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2110
        0,  1,  2,  3,  4,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3110
        4,  5,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0210
        0,  4,  5,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1210
        0,  1,  4,  5,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2210
        0,  1,  2,  3,  4,  5,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3210
        4,  5,  6,  7,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0310
        0,  4,  5,  6,  7,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1310
        0,  1,  4,  5,  6,  7,  8, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2310
        0,  1,  2,  3,  4,  5,  6,  7,  8, -1, -1, -1, -1, -1, -1, -1,    // 3310
        8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0020
        0,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1020
        0,  1,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2020
        0,  1,  2,  3,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3020
        4,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0120
        0,  4,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1120
        0,  1,  4,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2120
        0,  1,  2,  3,  4,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3120
        4,  5,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0220
        0,  4,  5,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1220
        0,  1,  4,  5,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2220
        0,  1,  2,  3,  4,  5,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1,    // 3220
        4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0320
        0,  4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1320
        0,  1,  4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1, -1, -1,    // 2320
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1,    // 3320
        8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0030
        0,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1030
        0,  1,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2030
        0,  1,  2,  3,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1,    // 3030
        4,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0130
        0,  4,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1130
        0,  1,  4,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2130
        0,  1,  2,  3,  4,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1,    // 3130
        4,  5,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0230
        0,  4,  5,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1230
        0,  1,  4,  5,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1,    // 2230
        0,  1,  2,  3,  4,  5,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1,    // 3230
        4,  5,  6,  7,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1, -1,    // 0330
        0,  4,  5,  6,  7,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1, -1,    // 1330
        0,  1,  4,  5,  6,  7,  8,  9, 10, 11, -1, -1, -1, -1, -1, -1,    // 2330
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, -1, -1, -1, -1,    // 3330
       12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0001
        0, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1001
        0,  1, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2001
        0,  1,  2,  3, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3001
        4, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0101
        0,  4, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1101
        0,  1,  4, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2101
        0,  1,  2,  3,  4, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3101
        4,  5, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0201
        0,  4,  5, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1201
        0,  1,  4,  5, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2201
        0,  1,  2,  3,  4,  5, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3201
        4,  5,  6,  7, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0301
        0,  4,  5,  6,  7, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1301
        0,  1,  4,  5,  6,  7, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2301
        0,  1,  2,  3,  4,  5,  6,  7, 12, -1, -1, -1, -1, -1, -1, -1,    // 3301
        8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0011
        0,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1011
        0,  1,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2011
        0,  1,  2,  3,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3011
        4,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0111
        0,  4,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1111
        0,  1,  4,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2111
        0,  1,  2,  3,  4,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3111
        4,  5,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0211
        0,  4,  5,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1211
        0,  1,  4,  5,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2211
        0,  1,  2,  3,  4,  5,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 3211
        4,  5,  6,  7,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0311
        0,  4,  5,  6,  7,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1311
        0,  1,  4,  5,  6,  7,  8, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 2311
        0,  1,  2,  3,  4,  5,  6,  7,  8, 12, -1, -1, -1, -1, -1, -1,    // 3311
        8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0021
        0,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1021
        0,  1,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2021
        0,  1,  2,  3,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3021
        4,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0121
        0,  4,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1121
        0,  1,  4,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2121
        0,  1,  2,  3,  4,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 3121
        4,  5,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0221
        0,  4,  5,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1221
        0,  1,  4,  5,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2221
        0,  1,  2,  3,  4,  5,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1,    // 3221
        4,  5,  6,  7,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0321
        0,  4,  5,  6,  7,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 1321
        0,  1,  4,  5,  6,  7,  8,  9, 12, -1, -1, -1, -1, -1, -1, -1,    // 2321
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 12, -1, -1, -1, -1, -1,    // 3321
        8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0031
        0,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1031
        0,  1,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2031
        0,  1,  2,  3,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1,    // 3031
        4,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0131
        0,  4,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1131
        0,  1,  4,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 2131
        0,  1,  2,  3,  4,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1,    // 3131
        4,  5,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0231
        0,  4,  5,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1,    // 1231
        0,  1,  4,  5,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1,    // 2231
        0,  1,  2,  3,  4,  5,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1,    // 3231
        4,  5,  6,  7,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1,    // 0331
        0,  4,  5,  6,  7,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1, -1,    // 1331
        0,  1,  4,  5,  6,  7,  8,  9, 10, 11, 12, -1, -1, -1, -1, -1,    // 2331
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, -1, -1, -1,    // 3331
       12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0002
        0, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1002
        0,  1, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2002
        0,  1,  2,  3, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3002
        4, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0102
        0,  4, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1102
        0,  1,  4, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2102
        0,  1,  2,  3,  4, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3102
        4,  5, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0202
        0,  4,  5, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1202
        0,  1,  4,  5, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2202
        0,  1,  2,  3,  4,  5, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 3202
        4,  5,  6,  7, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0302
        0,  4,  5,  6,  7, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1302
        0,  1,  4,  5,  6,  7, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 2302
        0,  1,  2,  3,  4,  5,  6,  7, 12, 13, -1, -1, -1, -1, -1, -1,    // 3302
        8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0012
        0,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1012
        0,  1,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2012
        0,  1,  2,  3,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 3012
        4,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0112
        0,  4,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1112
        0,  1,  4,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2112
        0,  1,  2,  3,  4,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 3112
        4,  5,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0212
        0,  4,  5,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1212
        0,  1,  4,  5,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2212
        0,  1,  2,  3,  4,  5,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 3212
        4,  5,  6,  7,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0312
        0,  4,  5,  6,  7,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 1312
        0,  1,  4,  5,  6,  7,  8, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 2312
        0,  1,  2,  3,  4,  5,  6,  7,  8, 12, 13, -1, -1, -1, -1, -1,    // 3312
        8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0022
        0,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1022
        0,  1,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2022
        0,  1,  2,  3,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 3022
        4,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0122
        0,  4,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1122
        0,  1,  4,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2122
        0,  1,  2,  3,  4,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 3122
        4,  5,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0222
        0,  4,  5,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1222
        0,  1,  4,  5,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 2222
        0,  1,  2,  3,  4,  5,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1,    // 3222
        4,  5,  6,  7,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 0322
        0,  4,  5,  6,  7,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 1322
        0,  1,  4,  5,  6,  7,  8,  9, 12, 13, -1, -1, -1, -1, -1, -1,    // 2322
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 12, 13, -1, -1, -1, -1,    // 3322
        8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0032
        0,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1032
        0,  1,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 2032
        0,  1,  2,  3,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1,    // 3032
        4,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0132
        0,  4,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 1132
        0,  1,  4,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 2132
        0,  1,  2,  3,  4,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1,    // 3132
        4,  5,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1, -1,    // 0232
        0,  4,  5,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1, -1,    // 1232
        0,  1,  4,  5,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1,    // 2232
        0,  1,  2,  3,  4,  5,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1,    // 3232
        4,  5,  6,  7,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1, -1,    // 0332
        0,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1, -1,    // 1332
        0,  1,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, -1, -1, -1, -1,    // 2332
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, -1, -1,    // 3332
       12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0003
        0, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1003
        0,  1, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2003
        0,  1,  2,  3, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 3003
        4, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0103
        0,  4, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1103
        0,  1,  4, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2103
        0,  1,  2,  3,  4, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 3103
        4,  5, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0203
        0,  4,  5, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1203
        0,  1,  4,  5, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 2203
        0,  1,  2,  3,  4,  5, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 3203
        4,  5,  6,  7, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 0303
        0,  4,  5,  6,  7, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 1303
        0,  1,  4,  5,  6,  7, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 2303
        0,  1,  2,  3,  4,  5,  6,  7, 12, 13, 14, 15, -1, -1, -1, -1,    // 3303
        8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0013
        0,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1013
        0,  1,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 2013
        0,  1,  2,  3,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 3013
        4,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0113
        0,  4,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1113
        0,  1,  4,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 2113
        0,  1,  2,  3,  4,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 3113
        4,  5,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0213
        0,  4,  5,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 1213
        0,  1,  4,  5,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 2213
        0,  1,  2,  3,  4,  5,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 3213
        4,  5,  6,  7,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 0313
        0,  4,  5,  6,  7,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 1313
        0,  1,  4,  5,  6,  7,  8, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 2313
        0,  1,  2,  3,  4,  5,  6,  7,  8, 12, 13, 14, 15, -1, -1, -1,    // 3313
        8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0023
        0,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 1023
        0,  1,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 2023
        0,  1,  2,  3,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 3023
        4,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,    // 0123
        0,  4,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 1123
        0,  1,  4,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 2123
        0,  1,  2,  3,  4,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 3123
        4,  5,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 0223
        0,  4,  5,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 1223
        0,  1,  4,  5,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 2223
        0,  1,  2,  3,  4,  5,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1,    // 3223
        4,  5,  6,  7,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 0323
        0,  4,  5,  6,  7,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 1323
        0,  1,  4,  5,  6,  7,  8,  9, 12, 13, 14, 15, -1, -1, -1, -1,    // 2323
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 12, 13, 14, 15, -1, -1,    // 3323
        8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,    // 0033
        0,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 1033
        0,  1,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 2033
        0,  1,  2,  3,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1,    // 3033
        4,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1,    // 0133
        0,  4,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 1133
        0,  1,  4,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 2133
        0,  1,  2,  3,  4,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1,    // 3133
        4,  5,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1,    // 0233
        0,  4,  5,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1,    // 1233
        0,  1,  4,  5,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1,    // 2233
        0,  1,  2,  3,  4,  5,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1,    // 3233
        4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1,    // 0333
        0,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1, -1,    // 1333
        0,  1,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, -1, -1,    // 2333
        0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15     // 3333
    };


}
