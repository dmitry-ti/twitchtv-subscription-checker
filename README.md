# twitchtv-subscription-checker
simple java server which checks if a user is subscribed to a twitch channel

Deployment:

1. build application by executing the command:
mvn install

2. copy war file to tomcat webapps folder

3. create subform.properties file:
client_id = <twitch application id>
client_secret = <twitch application secret>
redirect_uri = http://<server>/subform/auth
channel_name = <twitch channel name>
