# Magisk runs this while installing the module.
SKIPUNZIP=0

if [ "$API" -lt 31 ]; then
  abort "! PhoneLink needs Android 12 or newer"
fi

ui_print "- PhoneLink: installing as a privileged system app"
ui_print "- Turning on the Bluetooth hands-free and phonebook profiles"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "- Done. Restart the player, then open the PhoneLink app."
