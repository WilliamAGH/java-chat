#!/usr/bin/env python3
"""
Recover partially corrupted GZIP cache file containing Java serialized objects.
Extracts as much data as possible before the corruption point.
"""

import gzip
import struct
import sys
import pickle
from pathlib import Path

def recover_gzip_data(corrupted_file, output_file):
    """
    Attempt to recover data from a corrupted GZIP file.
    """
    recovered_data = bytearray()
    bytes_recovered = 0
    
    try:
        with open(corrupted_file, 'rb') as f:
            # Read the raw gzip data
            raw_data = f.read()
            
        # Try to decompress as much as possible
        try:
            with gzip.open(corrupted_file, 'rb') as gz:
                while True:
                    chunk = gz.read(1024 * 1024)  # Read 1MB at a time
                    if not chunk:
                        break
                    recovered_data.extend(chunk)
                    bytes_recovered += len(chunk)
        except Exception as e:
            print(f"Stopped recovery at {bytes_recovered} bytes due to: {e}")
    
    except Exception as e:
        print(f"Error opening file: {e}")
        return 0
    
    if bytes_recovered > 0:
        # Try to save the recovered data
        print(f"Recovered {bytes_recovered:,} bytes of uncompressed data")
        
        # Save raw recovered data
        raw_output = output_file.replace('.gz', '_recovered.raw')
        with open(raw_output, 'wb') as f:
            f.write(recovered_data)
        print(f"Saved raw data to: {raw_output}")
        
        # Try to recompress the valid data
        try:
            with gzip.open(output_file, 'wb') as gz_out:
                gz_out.write(recovered_data)
            print(f"Saved recompressed data to: {output_file}")
        except Exception as e:
            print(f"Could not recompress: {e}")
    
    return bytes_recovered

def analyze_java_serialized_data(raw_file):
    """
    Analyze Java serialized data to find object boundaries.
    Java serialization stream magic number: 0xACED
    Stream version: 0x0005
    """
    magic = b'\xac\xed\x00\x05'  # Java serialization header
    
    with open(raw_file, 'rb') as f:
        data = f.read()
    
    # Find all Java object boundaries
    objects = []
    pos = 0
    
    while pos < len(data):
        idx = data.find(magic, pos)
        if idx == -1:
            break
        objects.append(idx)
        pos = idx + 1
    
    print(f"\nFound {len(objects)} potential Java serialized objects")
    
    if objects:
        # Try to find the last complete object
        for i in range(len(objects) - 1, -1, -1):
            try:
                # Check if we can at least read the object header
                obj_start = objects[i]
                if obj_start + 100 < len(data):  # Minimum object size check
                    print(f"Last likely complete object starts at byte {obj_start:,}")
                    
                    # Truncate data at the start of the last incomplete object
                    if i < len(objects) - 1:
                        truncated_data = data[:objects[i+1]]
                        truncated_file = raw_file.replace('.raw', '_truncated.raw')
                        with open(truncated_file, 'wb') as f:
                            f.write(truncated_data)
                        print(f"Saved truncated data (up to last complete object): {truncated_file}")
                        
                        # Recompress truncated data
                        truncated_gz = truncated_file.replace('.raw', '.gz')
                        with gzip.open(truncated_gz, 'wb') as gz:
                            gz.write(truncated_data)
                        print(f"Recompressed truncated data: {truncated_gz}")
                        
                        return truncated_gz
                    break
            except:
                continue
    
    return None

if __name__ == "__main__":
    corrupted = "/Users/williamcallahan/Developer/git/idea/java-chat/data/embeddings-cache/embeddings_cache_recovery.gz"
    output = "/Users/williamcallahan/Developer/git/idea/java-chat/data/embeddings-cache/embeddings_cache_repaired.gz"
    
    print("=== GZIP Cache Recovery Tool ===")
    print(f"Input: {corrupted}")
    print(f"Output: {output}")
    print()
    
    # Step 1: Recover as much raw data as possible
    bytes_recovered = recover_gzip_data(corrupted, output)
    
    # Step 2: Analyze the recovered data for Java objects
    if bytes_recovered > 0:
        raw_file = output.replace('.gz', '_recovered.raw')
        repaired_file = analyze_java_serialized_data(raw_file)
        
        if repaired_file:
            print(f"\n✅ Successfully repaired cache file: {repaired_file}")
            print("You can rename this to embeddings_cache.gz to use it")
        else:
            print("\n⚠️ Could not fully repair the cache, but recovered data is available")