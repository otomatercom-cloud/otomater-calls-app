# Otomater Calls (Android)

Sends company-phone calls (+ recordings) of lead numbers to Odoo `custom_leads_19`.

## Build the APK (no Android Studio needed)
1. Create a private GitHub repo, upload this folder's contents, open Actions > "Build APK" > Run workflow.
2. Download the artifact `OtomaterCalls-apk` (app-debug.apk) and install it on the phones.
   (Or open the folder in Android Studio and press Run.)

## Set up each phone
1. In Odoo: My Profile > Account Security > New API Key (name it "Phone"), copy the key.
2. Open the app: enter the Odoo address and the key, then press buttons 1 to 4 in order.
3. Turn on the phone's own **call recording** (Phone app > Settings > Call recording > Record all calls).
