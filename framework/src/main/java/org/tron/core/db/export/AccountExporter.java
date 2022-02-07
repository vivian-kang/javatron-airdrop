package org.tron.core.db.export;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import com.alibaba.fastjson.JSON;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.actuator.VMActuator;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionRetCapsule;
import org.tron.core.db.*;
import org.tron.core.exception.BadItemException;
import org.tron.core.services.interfaceOnSolidity.WalletOnSolidity;
import org.tron.core.store.AccountStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.TransactionRetStore;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.contract.SmartContractOuterClass;

@Slf4j(topic = "exporter")
@Component
public class AccountExporter {

  public static final String FILE_NAME = "accounts.csv";
  //private  static TransactionRetStore transactionRetStore ;
  private  static BlockStore blockStore;
  //private  static BlockIndexStore blockIndexStore;
  private static VMActuator vmActuator = new VMActuator(true);
  static Set<String> trc20Address; // all trc20 address which accounts need to be exported

  private static WalletOnSolidity walletOnSolidity;
  private static long StartBlockHeight=0;

  @Autowired
  public void setWalletOnSolidity(WalletOnSolidity walletOnSolidity) {
    AccountExporter.walletOnSolidity = walletOnSolidity;
  }



  public void export(long height, AccountStore accountStore, BlockCapsule blockCapsule, TransactionRetStore transactionRetStore, BlockIndexStore blockIndexStore, long startBlockHeight) {
    StartBlockHeight = startBlockHeight;
    logger.info("height: {} , export account data", height);
    System.out.println(" >>> start export account data at block height: " + height);
    //trc20Address.clear();
    //load contract address from trc20.json file
    try {
      File trc20TokensFile = new File("trc20.json");
      if(trc20TokensFile.exists())
      {
        InputStream inputStream1 = new FileInputStream(trc20TokensFile);
        trc20Address = new HashSet<>(JSON.parseObject(inputStream1, List.class));
        System.out.println(" >>> load from trc20.json,"+ trc20Address.size() + " trc20 addresses");
        for (String str: trc20Address) {
          System.out.println(" >>> trc20Address: " + str);
          System.out.flush();
        }
      }

    } catch (Exception e){
      logger.error("Failed to load trc20 address from trc20.json file", e);
    }

    exportAll(accountStore, height);
    //exportNormal(accountStore, height);
    //exportContract(accountStore, height);
    //exportAssetIssue(accountStore, height);

    if(trc20Address!=null && trc20Address.size()>0)
    {
      exportTrc20Holder(height, blockCapsule,transactionRetStore,blockIndexStore);
    }

    System.out.println(" >>> finish export account data at block height: " + height);
  }



  private void exportAll(AccountStore accountStore,
                         long fingerprint) {

    AtomicLong total = new AtomicLong(0);

    System.out.printf(" >>> start exporting trx snapshot\n");
    System.out.flush();
    Map<String, Long> accounts = loopAccountStore(accountStore, null, total);
    System.out.printf(" >>> export %d All accounts, total %s trx(SUN)\n", accounts.size(), total.toString());
    System.out.flush();


    String filename = "block_" + fingerprint + "_all_" + FILE_NAME;
    writeCSVFile(total, accounts, filename);
    System.out.println(" >>> finish exporting account data" );
    System.out.flush();
  }

  private void exportNormal(AccountStore accountStore, long fingerprint) {
    AtomicLong total = new AtomicLong(0);
    AtomicLong totalFromJust = new AtomicLong(0);

    Map<String, Long> accounts = loopAccountStore(accountStore, AccountType.Normal, total);
    logger.info("export {} Normal accounts, total {} trx(SUN)", accounts.size(), total);

    String filename = "block_" + fingerprint + "_normal_" + FILE_NAME;
    writeCSVFile(total, accounts, filename);
  }

  private void exportContract(AccountStore accountStore, long fingerprint) {
    AtomicLong total = new AtomicLong(0);
    Map<String, Long> accounts = loopAccountStore(accountStore, AccountType.Contract, total);
    logger.info("export {} Contract accounts, total {} trx(SUN)", accounts.size(), total);

    String filename = "block_" + fingerprint + "_contract_" + FILE_NAME;
    writeCSVFile(total, accounts, filename);
  }

  private void exportAssetIssue(AccountStore accountStore, long fingerprint) {
    AtomicLong total = new AtomicLong(0);
    Map<String, Long> accounts = loopAccountStore(accountStore, AccountType.AssetIssue, total);
    logger.info("export {} AssetIssue accounts, total {} trx(SUN)", accounts.size(), total);

    String filename = "block_" + fingerprint + "_assetissue_" + FILE_NAME;
    writeCSVFile(total, accounts, filename);
  }


  /**
   * This is used for iterate AccountStore. Support simple filter for different type of Account.
   *
   * @param: accountStore  Iterable database of account.
   * @param: type          AccountType for filter.
   * @param: total         Output param, used for recording the total trx amount.
   * @return: accounts     Map<String, long> Key is account address of Base58Check format; Value is
   * Trx amount(unit SUN) this address holds, including balance, frozen balance for self's bandwidth
   * and energy, frozen balance for others' bandwidth and energy.
   */
  private Map<String, Long> loopAccountStore(AccountStore accountStore, AccountType type,
                                             AtomicLong total) {
    Map<String, Long> accounts = new HashMap<>();

    Iterator<Entry<byte[], AccountCapsule>> iterator = accountStore.iterator();
    System.out.println(" >>> loopAccountStore, accountStore.size " +accountStore.size());
    System.out.flush();
    long count = 0;
    while (iterator.hasNext()) {
      Entry<byte[], AccountCapsule> entry = iterator.next();
      AccountCapsule accountCapsule = entry.getValue();

      if ((++count) % 100000 == 0) {
        System.out.println(" >>> loopAccountStore. count: " + count + " time:" + System.currentTimeMillis());
        System.out.flush();
      }

      if (type != null && accountCapsule.getType() != type) {
        continue;
      }

      String address = StringUtil.encode58Check(entry.getKey());
      long trx = accountCapsule.getBalance() + accountCapsule.getFrozenBalance() + accountCapsule
              .getEnergyFrozenBalance() + accountCapsule.getDelegatedFrozenBalanceForBandwidth()
              + accountCapsule.getDelegatedFrozenBalanceForEnergy();

      if (trx > 0) {
        total.getAndAdd(trx);
      }

      accounts.put(address, trx);
    }

    return accounts;
  }

  /**
   * This is used for writing the account data to csv file.
   *
   * @param: accounts      Map<String, long> Key is account address of Base58Check format; Value is
   * Trx amount(unit SUN).
   * @param: total         The total trx amount.
   * @param: filename      csv file name.
   */
  private static void writeCSVFile(AtomicLong total, Map<String, Long> accounts, String filename) {
    try (CSVPrinter printer = new CSVPrinter(new FileWriter(filename),
            CSVFormat.EXCEL.withHeader("address", "balance"))) {
      printer.printRecord("total", total.get());
      accounts.forEach((k, v) -> {
        String address = k;
        long balance = v;
        try {
          printer.printRecord(address, balance);
        } catch (Exception e1) {
          logger.error("address {} write error.", address);
        }
      });
    } catch (Exception e) {
      logger.error("export error", e);
    }
  }

  private static void writeContractHolderCSVFile( Map<String, String> accounts, String filename) {

    try {
      BufferedWriter out = new BufferedWriter(new FileWriter(filename));
      accounts.forEach((k,v) ->{
        String address = k;
        String balance = v;
        try {
          out.write(address+","+balance+"\n");
        } catch (Exception e1) {
          logger.error("address {} write error.", address);
        }

      });

      out.close();
      logger.info("export "+filename + " successfully");
    } catch (IOException e) {
      logger.error("export {} error: {}", filename, e);
    }

  }


  private static void exportTrc20Holder(long headBlockNum, BlockCapsule blockCapsule, TransactionRetStore transactionRetStore, BlockIndexStore blockIndexStore) {
    System.out.printf(" >>> start exporting trc20 snapshot\n");
    System.out.flush();
    //避免trigger超时
    CommonParameter.getInstance().setDebug(true);

    long l1 = System.currentTimeMillis();
    Map<String, Set<String>> token20Map = new ConcurrentHashMap<>(1000);
    handlerMap(headBlockNum, token20Map, transactionRetStore,  blockIndexStore);
    System.out.println(" >>> token20Map key.size: " + token20Map.keySet().size());
    System.out.flush();

    final long sum = token20Map.values().stream().mapToLong(Set::size).sum();
    long l2 = System.currentTimeMillis();
    System.out.println(" >>> token20Map values.size:" + sum + ", cost:" + (l2 - l1));
    System.out.flush();

    l1 = System.currentTimeMillis();
    handlerMapToExcel(headBlockNum, token20Map, blockCapsule);
    l2 = System.currentTimeMillis();
    System.out.println(" >>> finish exporting trc20 holder data, cost:" + (l2 - l1));
    System.out.flush();
    CommonParameter.getInstance().setDebug(false);
  }

  private static void handlerMap(long headBlockNum, Map<String, Set<String>> token20Map, TransactionRetStore transactionRetStore, BlockIndexStore blockIndexStore) {
    // 起始块高
    System.out.println(" >>>>>>>>>>> StartBlockHeight: " + StartBlockHeight);
    System.out.flush();
    LongStream.range(StartBlockHeight, headBlockNum+1).parallel().forEach(num -> {

      parseTrc20Map(num, token20Map , transactionRetStore,  blockIndexStore);

      if (num % (10 * 100000) == 0) {
        System.out.println(" >>>>>>>>>>> handlerMap, num:" + num);
      }
    });
  }


  public static void parseTrc20Map(Long blockNum, Map<String, Set<String>> tokenMap, TransactionRetStore transactionRetStore, BlockIndexStore blockIndexStore) {
    try {
      TransactionRetCapsule retCapsule = transactionRetStore
              .getTransactionInfoByBlockNum(ByteArray.fromLong(blockNum));

      if (retCapsule != null) {

        final List<Protocol.TransactionInfo> transactioninfoList = retCapsule.getInstance().getTransactioninfoList();
        transactioninfoList.parallelStream().forEach(item -> {
          List<Protocol.TransactionInfo.Log> logs = item.getLogList();

          logs.parallelStream().forEach(l -> handlerTrc20ToMap(l, tokenMap));
        });
      }
    } catch (BadItemException e) {
      logger.error("TRC20Parser: block: {} parse error {} ", blockNum, e.getMessage());
      e.printStackTrace();
    }
  }

  private static void handlerTrc20ToMap(Protocol.TransactionInfo.Log log,
                                        Map<String, Set<String>> tokenMap) {
    final List<ByteString> topicsList = log.getTopicsList();
    if (CollectionUtils.isEmpty(topicsList) || topicsList.size() < 2) {
      return;
    }

    final String topic0 = new DataWord(topicsList.get(0).toByteArray()).toHexString();
    String tokenAddress = StringUtil
            .encode58Check(TransactionTrace.convertToTronAddress(log.getAddress().toByteArray()));
    //System.out.println(" >>>>>>>>>>> log address:"+ Hex.toHexString(TransactionTrace.convertToTronAddress(log.getAddress().toByteArray())) );

    // 过滤tokenAddress为需要的trc20合约地址
    if(!trc20Address.contains(tokenAddress)){
      return;
    }
    switch (ConcernTopics.getBySH(topic0)) {
      case TRANSFER:
        if (topicsList.size() < 3) {

          return;
        }
        // 从tokenMap获取trc20合约对应的AccountAddressSet，如果没有，则新建，并放进tokenMap
        Set<String> accountAddressSet = tokenMap
                .computeIfAbsent(tokenAddress, k -> ConcurrentHashMap.newKeySet());
        String senderAddr = StringUtil.encode58Check(TransactionTrace
                .convertToTronAddress(new DataWord(topicsList.get(1).toByteArray()).getLast20Bytes()));
        String recAddr = StringUtil.encode58Check(TransactionTrace
                .convertToTronAddress(new DataWord(topicsList.get(2).toByteArray()).getLast20Bytes()));

        if (topicsList.size() == 3) {
          // is trc20
          accountAddressSet.add(senderAddr);
          accountAddressSet.add(recAddr);
        }
        break;
      case Deposit:
      case Withdrawal:
        accountAddressSet = tokenMap
                .computeIfAbsent(tokenAddress, k -> ConcurrentHashMap.newKeySet());
        String accountAddr = StringUtil.encode58Check(TransactionTrace
                .convertToTronAddress(new DataWord(topicsList.get(1).toByteArray()).getLast20Bytes()));
        accountAddressSet.add(accountAddr);
        break;
      default:
        return;
    }
  }

  private static void handlerMapToExcel(long headBlockNum, Map<String, Set<String>> tokenMap, BlockCapsule blockCapsule) {
    //final BlockCapsule blockCapsule = getBlockByNum(headBlockNum);
    final AtomicInteger count = new AtomicInteger();

    // 并行流triggerVM会报错，这里不使用并行流
    tokenMap.forEach(
            (tokenAddress, accountAddressSet) -> {
              try {

                Map<String, String> accounts = new HashMap<>();

                // 并行流triggerVM会报错，这里不使用并行流
                accountAddressSet.forEach(
                        accountAddress -> {

                          BigInteger trc20Balance =
                                  getTRC20Balance(accountAddress, tokenAddress, blockCapsule);
                          if (trc20Balance != null && trc20Balance.compareTo(BigInteger.ZERO) > 0) {
                            accounts.put(accountAddress, trc20Balance.toString(10));

                          }

                          if (count.incrementAndGet() % 10000 == 0) {
                            System.out.println(
                                    " >>> token:"
                                            + tokenAddress
                                            + ", dec:"
                                            + 0
                                            + ", time:"
                                            + System.currentTimeMillis());
                          }
                        });

                logger.info("export {} {} accounts", accounts.size(), tokenAddress);

                String filename = "block_" + headBlockNum + "_"+ tokenAddress +"_" + FILE_NAME;
                writeContractHolderCSVFile( accounts, filename);

              } catch (Exception ex) {
                logger.error("exception occurs when exporting trc20 holders", ex);
              }
            });

  }


/*  private static BlockCapsule getBlockByNum(long num) {
    BlockCapsule blockCapsule = null;
    try {
      blockCapsule = blockStore.get(blockIndexStore.get(num).getBytes());
    } catch (Exception e) {
      logger.error(" >>> get block error, num:{}", num);
    }
    return blockCapsule;
  }
*/

  private static BigInteger getTRC20Balance(String ownerAddress, String contractAddress,
                                            BlockCapsule baseBlockCap) {
    // balanceOf(address)
    byte[] data = Bytes.concat(Hex.decode("70a082310000000000000000000000"),
            Commons.decodeFromBase58Check(ownerAddress));
    ProgramResult result = triggerFromVM(contractAddress, data, baseBlockCap);
    if (result != null
            && !result.isRevert() && StringUtils.isEmpty(result.getRuntimeError())
            && result.getHReturn() != null) {
      try {
        return toBigInteger(result.getHReturn());
      } catch (Exception e) {
        logger.error("", e);
      }
    }
    return null;
  }


  private static ProgramResult triggerFromVM(String contractAddress, byte[] data,
                                             BlockCapsule baseBlockCap) {
    SmartContractOuterClass.TriggerSmartContract.Builder build = SmartContractOuterClass.TriggerSmartContract
            .newBuilder();
    build.setData(ByteString.copyFrom(data));
    build.setOwnerAddress(ByteString.EMPTY);
    build.setCallValue(0);
    build.setCallTokenValue(0);
    build.setTokenId(0);
    build.setContractAddress(ByteString
            .copyFrom(Objects.requireNonNull(Commons.decodeFromBase58Check(contractAddress))));
    TransactionCapsule trx = new TransactionCapsule(build.build(),
            Protocol.Transaction.Contract.ContractType.TriggerSmartContract);
    Protocol.Transaction.Builder txBuilder = trx.getInstance().toBuilder();
    Protocol.Transaction.raw.Builder rawBuilder = trx.getInstance().getRawData().toBuilder();
    rawBuilder.setFeeLimit(1000000000L);
    txBuilder.setRawData(rawBuilder);

    TransactionContext context = new TransactionContext(baseBlockCap,
            new TransactionCapsule(txBuilder.build()),
            StoreFactory.getInstance(), true,
            false);
    try {
      vmActuator.validate(context);
      vmActuator.execute(context);
    } catch (Exception e) {
      logger.warn("{} trigger failed!", contractAddress);
    }
    return context.getProgramResult();
  }

  private static BigInteger toBigInteger(byte[] input) {
    if (input != null && input.length > 0) {
      try {
        if (input.length > 32) {
          input = Arrays.copyOfRange(input, 0, 32);
        }

        String hex = Hex.toHexString(input);
        return hexStrToBigInteger(hex);
      } catch (Exception e) {
      }
    }
    return null;
  }

  private static BigInteger hexStrToBigInteger(String hexStr) {
    if (!StringUtils.isEmpty(hexStr)) {
      try {
        return new BigInteger(hexStr, 16);
      } catch (Exception e) {
      }
    }
    return null;
  }


  private enum ConcernTopics {
    TRANSFER("Transfer(address,address,uint256)",
            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
    Withdrawal("Withdrawal(address,uint256)",
            "7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65"),
    Deposit("Deposit(address,uint256)",
            "e1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c"),
    UNKNOWN("UNKNOWN()",
            "0c78932dd210147f42a4ec6c5a353697626c4043d49be5f063518e57f3399e61");

    @Getter
    private String sign;
    @Getter
    private String signHash;


    ConcernTopics(String sign, String signHash) {
      this.sign = sign;
      this.signHash = signHash;
    }

    public static Boolean MatchSignHash(String dist) {
      for (ConcernTopics value : ConcernTopics.values()) {
        if (value.signHash.equals(dist)) {
          return true;
        }
      }
      return false;
    }

    public static ConcernTopics getBySH(String signHa) {
      for (ConcernTopics value : ConcernTopics.values()) {
        if (value.signHash.equals(signHa)) {
          return value;
        }
      }
      return UNKNOWN;
    }
  }

}
