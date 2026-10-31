# Prints the GTR 2e Bluetooth auth key from your Zepp account.
# The password is read hidden by huami-token itself and is not stored anywhere.
$email = Read-Host "Zepp account email"
& "$PSScriptRoot\huami-token\.venv\Scripts\huami-token.exe" -m amazfit -e $email -b
