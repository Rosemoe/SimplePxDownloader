# SimplePxDownloader
A simple downloader to get pixiv images with urls in a text file using multiple thread.
## Why I create this   
I have recently gotten a lots of urls of images in Pixiv by using Pxer.   
After that I do not know how to deal with these urls as I do not have a tool to create batch tasks.   
So I created an simple downloader with single thread.   
However, because I am in China,where has a slow speed for Pixiv,I was hard to download those images from i.pximg.net(Unaccessible in China if you try to connection directly).   
So I started to use i.pixiv.cat as a proxy.   
The speed became faster to 300KB/s but this is still not helpful.   
I suddenly wanted to learn how to download files in multiple threads and I managed to make file downloader with multiple threads(Just this one).   
## How to use this Downloader   
Put a file named 'urls.txt' in the same directory(Split urls with newline).   
Open your Terminal and enter:   
```Bash
javac Downloader.java   
java Downloader
```   
Your download will start at once.   
You are able to change max thread count and switch in Downloader.java.   
After a download is complete, we will pause for 200ms due not to be banned by the website.    
