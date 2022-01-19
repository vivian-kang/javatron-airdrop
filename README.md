<h1 align="center">
  <br>
  <img width=20% src="https://github.com/tronprotocol/wiki/blob/master/images/java-tron.jpg?raw=true">
  <br>
  java-tron 
  <br>
</h1>

<h4 align="center">
  Java implementation of the <a href="https://tron.network">Tron Protocol</a>
</h4>


<p align="center">
  <a href="https://gitter.im/tronprotocol/allcoredev">
    <img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667">
  </a>

  <a href="https://travis-ci.org/tronprotocol/java-tron">
    <img src="https://travis-ci.org/tronprotocol/java-tron.svg?branch=develop">
  </a>

  <a href="https://codecov.io/gh/tronprotocol/java-tron">
    <img src="https://codecov.io/gh/tronprotocol/java-tron/branch/develop/graph/badge.svg" />
  </a>

  <a href="https://github.com/tronprotocol/java-tron/issues">
    <img src="https://img.shields.io/github/issues/tronprotocol/java-tron.svg">
  </a>

  <a href="https://github.com/tronprotocol/java-tron/pulls">
    <img src="https://img.shields.io/github/issues-pr/tronprotocol/java-tron.svg">
  </a>

  <a href="https://github.com/tronprotocol/java-tron/graphs/contributors">
    <img src="https://img.shields.io/github/contributors/tronprotocol/java-tron.svg">
  </a>

  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/tronprotocol/java-tron.svg">
  </a>
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> •
  <a href="#deploy">Deploy</a> •
  <a href="#Deployment">Deployment</a> •
  <a href="#Channel">Channel</a> •
  <a href="#Contribution">Contribution</a> •
  <a href="#Resources">Resources</a>
</p>

## Introduction
This is a demo project for exporting TRX and TRC20 token data when node is running. This project is based on java-tron GreatVoyage-v4.4.3(Pythagoras). And added exporting data related codes.


## What new code have been added
1. Add a new http api to set the block height ("block_number") and block timestamp ("timestamp")  at which the node will export data. an opition config item is "start_block_height", it is used to set the start block height for scanning TRC20 token hoder
   
   https://github.com/vivian-kang/java-tron-airdrop/blob/main/framework/src/main/java/org/tron/core/services/http/FullNodeHttpApiService.java#L293 
   https://github.com/vivian-kang/java-tron-airdrop/blob/main/framework/src/main/java/org/tron/core/services/http/ExportAccountServlet.java 


2. Add account exporting code in /framework/src/main/java/org/tron/core/db/export/



## How to do data exporting before airdrop

1. Start the fullnode

2. If you need to export trc20 token holder, please put a json file named trc20.json into fullnode folder which will include all trc20 tokens to be scanned.

3. Set block height and block timestamp and start block height (optional) through http api: /wallet/exportaccount  
   For example: http://127.0.0.1:16887/wallet/exportaccount?block_number=950&timestamp=1642153902000  or http://127.0.0.1:16887/wallet/exportaccount?block_number=5625&timestamp=16421&start_block_height=40 
   

4. After the specified block arrived, or the time is up, the node will export the account data, you can find the exported files on the node directory:

   * block_950_all_accounts.csv  : includes all accounts which hold TRX
   * block_6217_TM5wBYLNH5o5gLFNQhJ73H9WgRUgU3PmvC_accounts.csv  : includes all accounts which hold TM5wBYLNH5o5gLFNQhJ73H9WgRUgU3PmvC token
 
 

5. After exporting the data, the node continues to synchronize data from other node 

