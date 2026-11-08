# RipMe Vibe Coded Edition

Hi this is a ripme fork where I vibecoded a fix for the famous 403 error which really annoyed me when trying to download stuff.
## DO NOT CLICK THE UPDATE BUTTON WITHIN THE SOFTWARE OR ELSE IT CHANGES TO THE REGULAR VERSION OF RIPME WITHOUT THiS FIX OF MINE. 

[Here's the link to the original ripme page](https://github.com/RipMeApp/ripme)
[![Licensed under the MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](/LICENSE.txt)
[the How To Run wiki](https://github.com/ripmeapp/ripme/wiki/How-To-Run-RipMe).

## About

RipMe is an album downloader (or "ripper") for various websites. This is a cross-platform tool that runs on *your* computer. Works on Windows, Linux, and Mac.

![Screenshot](https://i.imgur.com/UCQNjeg.png)

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


## Installation

On macOS, there is a [cask](https://github.com/Homebrew/homebrew-cask/blob/main/Casks/r/ripme.rb).

```
brew install --cask ripme && xattr -d com.apple.quarantine /Applications/ripme.jar
```

## Changelog

[Changelog](/ripme.json) **(ripme.json)**

# Features

- Quickly downloads all images in an online album. [See supported sites](https://github.com/ripmeapp/ripme/wiki/Supported-Sites)
- Easily re-rip albums to fetch new content
- Built in updater
- Skips already downloaded images by default
- Can auto skip e-hentai and nhentai albums containing certain tags. [See here for how to enable](https://github.com/RipMeApp/ripme/wiki/Config-options#nhentaiblacklisttags)
- Download a range of urls. [See here for how](https://github.com/RipMeApp/ripme/wiki/How-To-Run-RipMe#downloading-a-url-range)

## List of Supported Sites

See the full list of [Supported Sites](https://github.com/ripmeapp/ripme/wiki/Supported-Sites) in the wiki.

Note: Websites change over time and therefore rippers, which fundamentally depend on website layouts, can break at any time.
Feel free to open an issue if you notice something not working, but please search the list of issues to see if it's already been reported.

The list of supported rippers includes:

- imgur
- twitter (currently broken, needs to be updated for X)
- tumblr
- instagram
- flickr
- photobucket
- reddit
- deviantart (currently broken by major changes to the site)
- ... and [more](https://github.com/ripmeapp/ripme/wiki/Supported-Sites)!

## Sites Answering "Status Code 403"

Some sites refuse requests that do not carry the session cookies of a logged-in browser. RipMe can borrow those cookies from the browser you already use:

1. Log in to the site in Firefox, Edge, Chrome or Brave.
2. In RipMe open *Configuration → Site cookies...*, enter the domain (for example `reddit.com`)
   and press **Import from browser**.

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

If your browser cannot be read, use Firefox for the login, or copy the `Cookie` request header by
hand as described in the Site cookies dialog.


## Compiling & Building

The project uses [Gradle](https://gradle.org).

In particular, we use the `gradlew` script (`gradlew.bat` on Windows) 
to build the app and run tests.

To build the .jar file and run the appropriate tests, navigate to the
root project directory and run `gradlew` with the appropriate commands
(see below). Ensure that you manually test that your change works,
add or update the corresponding test, and run at least the test(s)
that are relevant to your change.

For example, if you modify the Xhamster ripper, you might run this or
a similar command:

```
./gradlew clean build testAll --tests XhamsterRipperTest.testXhamster2Album
```

Here's a breakdown of the functionality of the `gradlew` scripts:

The `build` verb will build the `.jar`, the tests, and will also *run*
the tests ,except for disabled and flaky tests.

Thus `build` and `build test` are actually synonymous.

```bash
./gradlew build
./gradlew build test
```

You can get more specific with your test commands:

```bash
./gradlew clean build testAll --tests XhamsterRipperTest.testXhamster2Album
./gradlew clean build -x test --warning-mode all
```

To perform a clean rebuild, which is only necessary to see warnings for
unchanged files or if you believe that the incremental build is interfering
with the build picking up your changes, for example. That shouldn't be an issue
for gradle, so you should check for easier explanations like  whether you saved
your changes. :)

```bash
./gradlew clean
```

To build ("assemble") the .jar without running the tests, which is useful for 
manual verification of functionality of a work in progress, run the following:

```bash
./gradlew assemble
```

The generated JAR (java archive) in build/libs will include all
dependencies.

## Running Tests

Tests can be tagged as beeing slow, or flaky. The gradle build reacts to
the following combinations of tags:

- default is to run all tests without tag.
- testAll runs all tests.
- testFlaky runs tests with tag "flaky".
- testSlow runs tests with tag "slow".
- tests can be run by test class, or single test. Use "testAll" so it does
  not matter if a test is tagged or not.
- tests can give the full stack of an assertion, exception, or error if you pass `--info` to the command

```bash
./gradlew test
./gradlew testAll
./gradlew testFlaky
./gradlew testSlow
./gradlew testAll --tests XhamsterRipperTest
./gradlew testAll --tests XhamsterRipperTest.testXhamster2Album
./gradlew testAll --tests ChanRipperTest --info
```

Please note that some tests may fail as sites change and our rippers become out of date. Start by building and testing a released version of RipMe and then ensure that any changes you make do not cause more tests to break.
