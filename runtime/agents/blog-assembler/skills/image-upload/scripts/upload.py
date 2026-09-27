#!/usr/bin/env python3
"""图床上传占位脚本：请在此配置真实图床接口后使用。"""
import sys

def main():
    if len(sys.argv) < 2:
        print("usage: upload.py <image_path>")
        return
    path = sys.argv[1]
    print(f"[placeholder] uploading {path}")
    print("https://img.example.com/placeholder.jpg")

if __name__ == "__main__":
    main()