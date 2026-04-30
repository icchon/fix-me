package com.github.icchon;
import java.util.Map;

//TIP コードを<b>実行</b>するには、<shortcut actionId="Run"/> を押すか
// ガターの <icon src="AllIcons.Actions.Execute"/> アイコンをクリックします。
public class Main {
    public static void main(String[] args){
        int brokerPort = 15000;
        Map<Integer, String> mappings = Map.of(
                25000, "MARKET_A_ID",
                25001, "MARKET_B_ID"
        );
        Router server = new Router(brokerPort, mappings);
//        FixMessageBuilder builder = new FixMessageBuilder("|");
//        String msg = builder.create("100001").setMsgType("A").setField(34, "1").setField(55, "OIL").setField(38, "100").setField(44, "50.5").setTargetID("200001").build();
//        System.out.println(msg);
        server.run();
    }
}
