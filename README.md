# twitchtv-subscription-checker
simple java server which checks if a user is subscribed to a twitch channel

Deployment:

1. build application by executing the command:
mvn install

2. copy war file to tomcat webapps folder

3. create subckecker.properties file:
client_id = abc
client_secret = def
redirect_uri = http://server/subckecker/auth
channel_name = channel
