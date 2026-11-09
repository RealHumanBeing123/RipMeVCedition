# RipMe Vibe Coded Edition

Hi this is a ripme fork where I vibecoded a fix for the famous 403 error which really annoyed me when trying to download stuff.
## DO NOT CLICK THE UPDATE BUTTON WITHIN THE SOFTWARE OR ELSE IT CHANGES TO THE REGULAR VERSION OF RIPME WITHOUT THiS FIX OF MINE. 
Currently, there are 3 websites that I know of that don't work. If you find any others then open one of those issue report ticket things or contact me someway somehow so I know to fix it.

[Here's the link to the original ripme page](https://github.com/RipMeApp/ripme)

## About

RipMe is an album downloader (or "ripper") for various websites. This is a cross-platform tool that runs on *your* computer. Works on Windows, Linux, and Mac.

Screenshots:

<img width="384" height="374" alt="ripmescreenshot" src="https://github.com/user-attachments/assets/cab50887-788b-4a2d-9919-7153878260ec" />

<img width="311" height="293" alt="ripme2" src="https://github.com/user-attachments/assets/a82edd30-7686-4429-85cb-cbda350214b7" />

## Download

Download `ripme.jar` from the [latest release](https://github.com/ripmeapp/ripme/releases/latest). For information about running the `.jar` file, see
[the How To Run wiki](https://github.com/ripmeapp/ripme/wiki/How-To-Run-RipMe).

## Minimum Requirements

Requires Java 25 or later to run. RipMe has been tested on and is confirmed working on Windows, Linux, and Mac.

Java 27 is the most modern possible Java version that allows us to support the most platforms; it's the default JDK on current Debian stable. S

## Supported Sites
Jump to:
- [List of Supported Sites](https://github.com/RipMeApp/ripme?tab=readme-ov-file#list-of-supported-sites)
If the site that you want isn't supported then open one of those support ticket thingies on my fork and I'll try to figure out how to add it.

## List of Supported Sites

The list of supported rippers includes:

- imgur
- twitter (currently broken, needs to be updated for X)
- tumblr
- instagram
- flickr
- photobucket
- reddit
- deviantart (currently broken by major changes to the site)
- [etc](https://github.com/ripmeapp/ripme/wiki/Supported-Sites)

## Sites Answering "Status Code 403"

Some sites refuse requests that do not carry the session cookies of a logged-in browser. RipMe can borrow those cookies from the browser you already use:

1. Log in to the site in Firefox, Edge, Chrome or Brave.
2. In RipMe open *Configuration → Site cookies...*, enter the domain (for example `reddit.com`)    and press **Import from browser**.

RipMe reads only the cookies of that one site, from the browser that is currently running (or any
installed one), and stores them on your computer next to the manually entered cookies. Tick
*Automatically import browser cookies when a site answers 401/403* to have RipMe do this by
itself the first time a site answers 401/403 (off by default).

Support by browser:

| Browser | Support |
|---|---|
| Firefox (Windows, macOS, Linux) | Full. |
| Chrome / Edge / Brave / Chromium on macOS | Works; macOS asks once for keychain access. |
| Chrome / Edge / Brave / Chromium on Linux | Works for cookies stored with the default key; keyring-protected cookies are skipped. |
| Chrome / Edge / Brave on Windows | Works for cookies using the classic DPAPI protection. Newer versions with *app-bound encryption* cannot be read by other programs; RipMe tells you so. |

If your browser cannot be read, use Firefox for the login, or copy the `Cookie` request header by hand as described in the Site cookies dialog.

## Compiling & Building

1. Download and unzip the folder to a place that makes you happy.
   
2. use CD for the place that you put it
   
3. Two choices, the first one skips tests and the second one will perform tests
   ``` gradlew.bat build -x test -PcustomVersion=1.0.0-dev```
   
   ```gradlew.bat test --tests "*BrowserCookieUnitTest" -PcustomVersion=1.0.0-dev```
The generated JAR (java archive) in build/libs will include all dependencies.
