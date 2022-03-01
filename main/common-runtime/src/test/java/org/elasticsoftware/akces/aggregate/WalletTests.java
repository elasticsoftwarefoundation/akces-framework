package org.elasticsoftware.akces.aggregate;

import org.testng.annotations.Test;


public class WalletTests {
    @Test
    public void testWalletAggregateCreation() {
        Wallet wallet = new Wallet();
        AggregateRuntime runtime = wallet.configure(AggregateProvider.getInstance().aggregate(wallet)).build();
    }
}
