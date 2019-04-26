# Setup
## Installation

* Installer OBS
* Installer BlackMagic Desktop Video (un reboot sera demandé) 
* Installer Decimator app

## Modification du fichier de scene

Télécharger ces fichiers et les mettre dans un dossier séparé:

* AM19Scene
* video_background.jpg

Puis, dans un terminal, aller dans le dossier et entrer ces commandes:

    sed 's?/Users/djavan/AndroidMakers19/VideoSetup?'`pwd`'?' AM19Scene.json > AM19SceneCopy.json 
    
    cp AM19SceneCopy.json AM19Scene.json

## Configuration du BlackMagic

* Connecter le BlackMagic à l’ordinateur
* Ouvrir BlackMagic Desktop Video
* Cliquer sur le petit bouton au centre
* Dans Video Input, sélectionner HDMI si l’input du BlackMagic est en HDMI, sinon, sélectionner SDI.

## Configuration d’OBS

* Ouvrir OBS
* Aller dans le menu Scene Collection -> Import 
* Sélectionner AM19Scene.json
* Brancher les deux cables USB-C (BlackMagic + AverMedia ou AverMedia + AverMedia)
* Si l’image de la camera n’est pas affichée, click droit sur Camera USB -> Properties et sélectionner le "BlackMagic ..." device (ou AverMedia pour les salles du bas). Refaire cette etape pour toutes les scenes contenant la camera.
* Si l’image des slides n’est pas affichée, click droit sur Slides USB -> Properties et sélectionner le "AverMedia .." device.
* Refaire cette étape pour toutes les scenes contenant les slides.
* Un fois la caméra branchée, refaire le crop de la vidéo dans OBS dans la scene MainScene. Pour ça, click droit sur la source Camera USB -> Transform -> Edit Transform.
* Configurer le format d’enregistrement:
* Aller dans Settings
** Dans Output, choisir mkv dans Output format
** Dans Video, choisir 1920x1080 pour les 2 résolutions. Choisir 30fps.

Bien penser a faire de la place sur son ordinateur !!!!

# Durant AndroidMakers

## Avant un talk

* Démarrer OBS
* Vérifier que les deux vidéos (caméra et slides) sont affiché sur le logiciel
* Vérifier que le son est bien affiché (barre verte en bas) 
* Cliquer sur "record video"
* Au démarrage du Talk, noter le début de la vidéo (en bas) dans un fichier texte nommé avec l'id du texte (voir template) 

## Pendant un talk
* Écouter le talk via les écouteurs afin de vérifier la prise de son. Si problème de son, prévenir régie (salle bas), Alex ou autre
* Regarder le frame rate de la vidéo (en bas à droite), si en dessous de 25fps, essayer de changer de scène, prévenir quelqu'un 
* Vérifier que le speaker est bien dans le cadre vidéo. Ajuster camera si cela arrive/arrivera trop souvent. Si seulement temporaire, ne pas hésiter à passer sur la scène "slides plein écran" en cliquant sur la scene FullScreen slides

## Après un talk
* Arrêter l'enregistrement
* Renommer enregistrement avec l'id du Talk (traitement automatique après)
