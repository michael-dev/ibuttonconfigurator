# iButtonConfigurator

This app is for configuring your DS1922 iButton using an DS2490 USB-to-1-Wire adapter.

## How does this app work?
This talks to the DS2490 USB adapter using Android USB api.
It uses this to talk to the DS1922 iButton.
You can use to it show what is stored on the iButton and to configure it.
You can share the measurements taken as text to any other compatible app installed locally using the Android sharing function.

## Does this app collect personal information?

No, it only does what is outlined above.

## Permissions needed

It will ask you for permission to access the USB device you selected (it is your 1-Wire USB adapter).

## Note on password protection

The password input is hashed (SHA-256) and first 8 Byte are used as password.
This enables use of variable-length passwords but might not conform to other implementations.

## Not implemented

- optionally use overdrive
- optionally use RESUME cmd for DS1922L/T https://www.analog.com/media/en/technical-documentation/data-sheets/ds1922l-ds1922t.pdf
