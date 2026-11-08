GiveFx 1.3 - effets spéciaux pour les objets du générateur de /give
================================================================

À quoi ça sert
--------------
Dans le générateur (section "Effets spéciaux"), tu choisis par exemple :
"Quand je casse un bloc -> explosion". Le générateur écrit cette règle dans
l'objet. Ce plugin lit la règle et la fait marcher sur le serveur.

Installation
------------
1. Compile le plugin :      mvn package
2. Prends le fichier        target/GiveFx.jar
3. Mets-le dans             plugins/  de ton serveur Paper
4. Redémarre le serveur.

Version de Paper
----------------
Dans pom.xml, la ligne <paper.version> doit correspondre à ta version de Paper.
Si ta version n'existe pas encore dans le dépôt, prends la plus proche.
Le code n'utilise que des fonctions stables de l'API.

Utilisation
-----------
1. Ouvre le générateur, choisis l'objet.
2. Dans "Effets spéciaux", choisis une idée toute faite ou crée une règle.
3. Copie la commande /give et colle-la dans le jeu.

Réglages (plugins/GiveFx/config.yml)
------------------------------------
allow-commands       false par défaut. L'action "Commande" est ignorée tant que
                     tu ne mets pas true. Garde false si des joueurs ont le
                     mode créatif : ils pourraient fabriquer un objet qui lance
                     des commandes en console.
max-explosion-power  puissance maximale d'une explosion (4 = une TNT)
max-radius           rayon maximal de "Casser autour"

Commande : /givefx reload   (permission givefx.admin)

Quand une règle se déclenche
----------------------------
- L'objet doit être en main principale (sauf "Je mange ou bois" : l'objet consommé).
- "Casser autour" ne passe pas par les plugins de protection (WorldGuard...).
- Une pause (en secondes) peut être réglée sur chaque règle.

Déclencheurs (le "Quand ?")
---------------------------
Je casse un bloc, je pose un bloc, clic droit, clic gauche, je frappe,
je tue, je tire (arc/arbalète), je mange ou bois, je m'accroupis,
je prends un coup, tant que je le tiens (chaque seconde),
tant que je le porte (chaque seconde).

Actions (le "Que se passe-t-il ?")
----------------------------------
Détruire : explosion, foudre, TNT amorcée, enflammer, casser autour, dégâts de zone.
Sur moi : se propulser, se téléporter, se soigner, nourrir, gagner de l'XP,
          effet de potion, stat temporaire (attribut), vol de vie, réparer l'objet.
Sur les autres : onde de choc, projeter en l'air, attirer, geler,
                 boule de feu, invoquer une créature.
Ambiance : particules et son, message, commande (désactivée par défaut).
Sans limites : dégâts multipliés (aucun plafond), dégâts reçus réduits (100 % = invincible),
               jamais de faim, vol comme en créatif, butin multiplié.

Conditions (facultatives, sur chaque règle)
-------------------------------------------
Chance en %, pause en secondes, et "seulement si" : accroupi, au sol,
en l'air, dans l'eau, vie basse.

Stats temporaires (attributs)
-----------------------------
L'action "Stat temporaire" ajoute un bonus à une stat (taille, vitesse, gravité...)
pendant quelques secondes. Avec "Tant que je le tiens" ou "Tant que je le porte",
mets 2 secondes : le bonus disparaît dès que tu poses l'objet.
Les bonus restants sont nettoyés quand un joueur se connecte ou au rechargement
du plugin, donc un redémarrage ne laisse rien de bloqué.

Un effet ne peut pas en déclencher un autre (anti-boucle).

Sans limites : comment ça marche
--------------------------------
Les attributs du jeu ont des plafonds (par exemple 2048 de dégâts, 30 d'armure) que
aucune commande ne peut dépasser. Le plugin contourne ça avec des effets à lui :
- Dégâts multipliés : agit sur le coup lui-même, donc pas de plafond.
- Dégâts reçus réduits : 100 % = plus aucun dégât.
- Jamais de faim : tant que l'objet est tenu (ou porté), la faim et la saturation ne
  baissent plus. Parfait pour la ruée (Lunge) de la lance.
- Vol : tant que l'objet est tenu (ou porté), le joueur peut voler en survie/aventure.
  Le vol est retiré dès qu'il pose l'objet.
- Butin multiplié : à utiliser avec "Je casse un bloc".
Pour ces effets (sauf le butin), choisis "Tant que je le tiens" ou "Tant que je le porte".

Événement quotidien (version 1.3)
---------------------------------
Dans config.yml, section daily-event : mets enabled: true pour l'activer.
Chaque jour, après l'heure "time", un bloc (par défaut un coffre) apparaît à
4-8 blocs de chaque joueur connecté, rempli d'objets tirés au hasard dans "loot".
Chaque objet a un "weight" (fréquence). Un message prévient le joueur.
Il n'y a qu'un bloc par jour et par joueur ; le jour est mémorisé dans
plugins/GiveFx/daily.txt, donc un redémarrage ne le fait pas revenir.

Objets rares : "drop-chance" (en %) est la chance, en cassant un bloc en survie,
de trouver un objet de la liste "loot" en plus.

Si tu avais déjà config.yml, ajoute la section daily-event à la fin
(copie-la depuis le nouveau fichier du plugin) : sans elle, rien ne se passe.

Objets custom dans l'événement quotidien (version 1.3)
------------------------------------------------------
Dans la liste "loot" de config.yml, un objet peut avoir un nom (name), une description
(lore) et des effets (fx). Les effets s'écrivent comme ceux du générateur :
  fx: [{t: right, a: summon, p: {mob: chicken, count: 3}, cd: 2}]
t = quand (right, left, hit, eat, held, worn, sneak...), a = action, p = réglages,
cd = pause en secondes, ch = chance en %. Les actions et leurs réglages sont les
mêmes que dans le générateur. Les effets qui durent (potion, attribut) se règlent
avec "sec" ; avec "held" ou "worn", garde une durée courte (2 à 3 s) pour qu'ils
disparaissent dès que l'objet est posé ou retiré.

Objets normaux et objets rares (version 1.3)
--------------------------------------------
Dans "loot" : un objet SANS "chance" est normal, tiré selon "weight".
Un objet AVEC "chance" (en %) est rare : à chaque tirage (chaque coffre, chaque
bloc cassé), il a cette chance d'apparaître, indépendamment des autres.
Exemple : chance: 0.001 = 0,001 %, soit 1 chance sur 100 000 par tirage.
Pour les blocs cassés, les objets rares sont testés à chaque casse ; le taux
"drop-chance" ne concerne que les objets normaux.
Pour tester un objet rare, mets temporairement une chance plus haute (ex. 5),
puis remets 0.001.

Éditeur de config (version 1.3)
-------------------------------
givefx-config-editeur.html ouvre dans le navigateur. Tu y règles les options et la
liste des objets, puis tu télécharges un config.yml prêt à mettre dans plugins/GiveFx/.

Événements à l'ouverture du coffre quotidien (version 1.3)
----------------------------------------------------------
Quand un joueur ouvre pour la première fois un coffre quotidien, un événement peut
se déclencher (section open-events du config.yml) : TNT, pluie de minerais, mimic,
poulets, XP, foudre visuelle, explosion, objet rare, feu d'artifice ou rien.
Chaque coffre ne déclenche qu'une fois. La chance se règle avec "chance" (en %).
Nouveaux événements d'ouverture : COWS, PIGS, SHEEP (animaux), JOKE (blague au hasard),
CAKE (gâteaux). Voir la liste complète dans config.yml.

Nouvelles actions (version 1.3)
-------------------------------
swap        : échange ta place avec le mob le plus proche (radius)
pig_taxi    : un cochon apparaît sous toi et tu montes dessus (sec : durée)
anvil_rain  : des enclumes tombent sur les mobs autour (count, radius)
shuffle     : mélange ta barre d'outils (les 9 premiers emplacements)
bounce      : rebond vers le haut (power)
broadcast   : message envoyé à tout le serveur (text)
summon      : ajoute height (fait tomber du ciel) et scale (taille, ex. 3 = géant)


Lucky Blocks dans les coffres quotidiens
----------------------------------------
Chaque coffre du jour contient entre 2 et 10 Lucky Blocks (plugin LuckyBlock requis).
Réglages : daily-event.lucky-blocks (enabled, chance en %, min, max) dans config.yml.

Build automatique GitHub
------------------------
Le fichier .github/workflows/build.yml compile le plugin à chaque push (Java 25, mvn package).
Le jar est dans GitHub > Actions > (dernier run) > Artifacts > GiveFx.
Pour une release : git tag v1.3.1 && git push --tags (le jar est joint à la release).

Enchantements sans limite
-------------------------
À l'enclume, deux enchantements identiques de même niveau donnent le niveau +1, sans plafond
vanilla, et sans « Trop cher ! ». Commande : /givefx enchant <enchantement> <niveau>
(objet en main, niveau 0 = retirer). Limite : 255 (plafond technique de Minecraft).
Réglages : section unlimited-enchants de config.yml.

Tout régler depuis Minecraft
----------------------------
/givefx            ouvre le menu de réglages (clic gauche +, clic droit -, Maj + clic = grand pas)
/givefx chest      pose le coffre du jour tout de suite près de toi (pour tester)
/givefx loot list | add [poids] | addrare <chance%> | remove <n> | clear
                   choisis ce que le coffre contient : tiens l'objet en main puis /givefx loot add
                   (nom, description et effets spéciaux de l'objet sont gardés)
Les réglages du menu sont enregistrés dans plugins/GiveFx/menu-settings.yml et passent avant
config.yml (config.yml n'est jamais réécrit). Menu > Réinitialiser pour revenir à config.yml.
Le coffre du jour doit être ACTIVÉ dans le menu (« Coffre quotidien ») pour apparaître tout seul.
