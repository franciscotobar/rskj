/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import static co.rsk.peg.FederationCreationException.Reason.ABOVE_MAX_SCRIPT_ELEMENT_SIZE;
import static org.junit.jupiter.api.Assertions.*;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptOpCodes;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import co.rsk.config.BridgeConstants;
import co.rsk.config.BridgeMainNetConstants;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.TestUtils;
import org.ethereum.crypto.ECKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class StandardMultisigFederationTest {
    private Federation federation;
    private NetworkParameters networkParameters;

    private List<BtcECKey> keys;
    private List<BtcECKey> sortedPublicKeys;
    private List<byte[]> rskAddresses;

    @BeforeEach
    void setUp() {
        BridgeConstants bridgeConstants = BridgeMainNetConstants.getInstance();

        networkParameters = bridgeConstants.getBtcParams();
        federation = bridgeConstants.getGenesisFederation();

        keys = federation.getBtcPublicKeys();
        sortedPublicKeys = keys.stream()
            .sorted(BtcECKey.PUBKEY_COMPARATOR).collect(Collectors.toList());

        List<ECKey> rskPubKeys = sortedPublicKeys.stream()
            .map(btcECKey -> ECKey.fromPublicOnly(btcECKey.getPubKey()))
            .collect(Collectors.toList());
        rskAddresses = rskPubKeys
            .stream()
            .map(ECKey::getAddress)
            .collect(Collectors.toList());
    }

    @Test
    void createInvalidFederation_aboveMaxScriptSigSize() {
        List<BtcECKey> newKeys = federation.getBtcPublicKeys();
        BtcECKey federator15PublicKey = BtcECKey.fromPublicOnly(
            Hex.decode("03b65684ccccda83cbb1e56b31308acd08e993114c33f66a456b627c2c1c68bed6")
        );

        // add one member to exceed redeem script size limit
        newKeys.add(federator15PublicKey);
        List<FederationMember> newMembers = FederationTestUtils.getFederationMembersWithBtcKeys(newKeys);
        Instant creationTime = federation.getCreationTime();
        FederationCreationException exception =
            assertThrows(FederationCreationException.class, () -> new StandardMultisigFederation(
                newMembers,
                creationTime,
                federation.creationBlockNumber,
                federation.btcParams
            ));
        assertEquals(ABOVE_MAX_SCRIPT_ELEMENT_SIZE, exception.getReason());
    }

    @Test
    void membersImmutable() {
        boolean exception = false;
        try {
            federation.getMembers().add(new FederationMember(new BtcECKey(), new ECKey(), new ECKey()));
        } catch (Exception e) {
            exception = true;
        }
        Assertions.assertTrue(exception);

        exception = false;
        try {
            federation.getMembers().remove(0);
        } catch (Exception e) {
            exception = true;
        }
        Assertions.assertTrue(exception);
    }

    @Test
    void testEquals_basic() {
        assertEquals(federation, federation);

        assertNotEquals(null, federation);
        assertNotEquals(federation, new Object());
        assertNotEquals("something else", federation);
    }

    @Test
    void testEquals_same() {
        Federation otherFederation = new StandardMultisigFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams()
        );

        assertEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentCreationTime() {
        Federation otherFederation = new StandardMultisigFederation(
            federation.getMembers(),
            federation.getCreationTime().plus(1, ChronoUnit.MILLIS),
            federation.getCreationBlockNumber(),
            networkParameters
        );
        assertEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentCreationBlockNumber() {
        Federation otherFederation = new StandardMultisigFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber() + 1,
            networkParameters
        );
        assertEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentNetworkParameters() {
        Federation otherFederation = new StandardMultisigFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );
        // Different network parameters will result in a different address
        assertNotEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentNumberOfMembers() {
        // remove federator14
        List<BtcECKey> newKeys = federation.getBtcPublicKeys();
        newKeys.remove(14);
        List<FederationMember> newMembers = FederationTestUtils.getFederationMembersWithKeys(newKeys);

        Federation otherFederation = new StandardMultisigFederation(
            newMembers,
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams()
        );

        Assertions.assertNotEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentMembers() {
        // replace federator15 with another pkey
        BtcECKey anotherPublicKey = BtcECKey.fromPublicOnly(
            Hex.decode("03b65694ccccda83cbb1e56b31308acd08e993114c33f66a456b627c2c1c68bed7")
        );
        List<BtcECKey> newKeys = federation.getBtcPublicKeys();
        newKeys.remove(14);
        newKeys.add(anotherPublicKey);
        List<FederationMember> differentMembers = FederationTestUtils.getFederationMembersWithKeys(newKeys);

        Federation otherFederation = new StandardMultisigFederation(
            differentMembers,
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            networkParameters
        );

        assertNotEquals(federation, otherFederation);
    }

    @Test
    void getP2SHScriptAndAddress() {
        Script p2shScript = federation.getP2SHScript();
        Address address = federation.getAddress();

        String expectedProgram = "a91451f103320b435b5fe417b3f3e0f18972ccc710a087";
        Address expectedAddress = Address.fromBase58(
            networkParameters,
            "39AHNvUmzaYgewA8yCtBtNsfRz7QD7ZJYi"
        );

        assertEquals(expectedProgram, Hex.toHexString(p2shScript.getProgram()));
        assertEquals(3, p2shScript.getChunks().size());
        assertEquals(
            address,
            p2shScript.getToAddress(networkParameters)
        );
        assertEquals(expectedAddress, address);
    }

    @Test
    void getRedeemScript() {
        Script expectedScript = new Script(Hex.decode("58210245ef34f5ee218005c9c21227133e8568a4f3f11aeab919c66ff7b816ae1ffeea2102481f02b7140acbf3fcdd9f72cf9a7d9484d8125e6df7c9451cfa55ba3b0772652102550cc87fa9061162b1dd395a16662529c9d8094c0feca17905a3244713d65fe82102566d5ded7c7db1aa7ee4ef6f76989fb42527fcfdcddcd447d6793b7d869e46f721027319afb15481dbeb3c426bcc37f9a30e7f51ceff586936d85548d9395bcc2344210294c817150f78607566e961b3c71df53a22022a80acbb982f83c0c8baac040adc2102ac1901b6fba2c1dbd47d894d2bd76c8ba1d296d65f6ab47f1c6b22afb53e73eb2102c6018fcbd3e89f3cf9c7f48b3232ea3638eb8bf217e59ee290f5f0cfb2fb925921031aabbeb9b27258f98c2bf21f36677ae7bae09eb2d8c958ef41a20a6e88626d26210340df69f28d69eef60845da7d81ff60a9060d4da35c767f017b0dd4e20448fb44210355a2e9bf100c00fc0a214afd1bf272647c7824eb9cb055480962f0c382596a70210372cd46831f3b6afd4c044d160b7667e8ebf659d6cb51a825a3104df6ee0638c62103b53899c390573471ba30e5054f78376c5f797fda26dde7a760789f02908cbad22103b65694ccccda83cbb1e56b31308acd08e993114c33f66a456b627c2c1c68bed62103f909ae15558c70cc751aff9b1f495199c325b13a9e5b934fd6299cd30ec50be85fae"));
        Script redeemScript = federation.getRedeemScript();
        assertEquals(expectedScript, redeemScript);

        int expectedChunks = sortedPublicKeys.size() + 3; // + 3 opcodes (OP_M, OP_N, OP_CHECKMULTISIG)
        assertEquals(expectedChunks, redeemScript.getChunks().size());

        int opM = ScriptOpCodes.getOpCode("" + federation.getNumberOfSignaturesRequired());
        assertEquals(opM, redeemScript.getChunks().get(0).opcode);

        for (int i = 0; i < sortedPublicKeys.size(); i++) {
            assertArrayEquals(sortedPublicKeys.get(i).getPubKey(), redeemScript.getChunks().get(i+1).data);
        }

        int opN = ScriptOpCodes.getOpCode("" + federation.getSize());
        assertEquals(opN, redeemScript.getChunks().get(redeemScript.getChunks().size() - 2).opcode);
        assertEquals(ScriptOpCodes.OP_CHECKMULTISIG, redeemScript.getChunks().get(redeemScript.getChunks().size() - 1).opcode);
    }

    @Test
    void getBtcPublicKeyIndex() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            Optional<Integer> index = federation.getBtcPublicKeyIndex(sortedPublicKeys.get(i));
            assertTrue(index.isPresent());
            assertEquals(i, index.get().intValue());
        }
        assertFalse(federation.getBtcPublicKeyIndex(BtcECKey.fromPrivate(BigInteger.valueOf(1234))).isPresent());
    }

    @Test
    void hasBtcPublicKey() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            assertTrue(federation.hasBtcPublicKey(sortedPublicKeys.get(i)));
        }
        assertFalse(federation.hasBtcPublicKey(BtcECKey.fromPrivate(BigInteger.valueOf(1234))));
    }

    @Test
    void hasMemberWithRskAddress() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            assertTrue(federation.hasMemberWithRskAddress(rskAddresses.get(i)));
        }

        byte[] nonFederateRskAddress = ECKey.fromPrivate(BigInteger.valueOf(1234)).getAddress();
        assertFalse(federation.hasMemberWithRskAddress(nonFederateRskAddress));
    }

    @Test
    void testToString() {
        assertEquals(
            "Got 8 of 15 signatures federation with address 39AHNvUmzaYgewA8yCtBtNsfRz7QD7ZJYi",
            federation.toString()
        );
    }

    @Test
    void isMember(){
        //Both valid params
        FederationMember federationMember = federation.getMembers().get(0);
        assertTrue(federation.isMember(federationMember));

        byte[] b = TestUtils.generateBytes("b",20);

        ECKey invalidRskKey = ECKey.fromPrivate(b);
        BtcECKey invalidBtcKey = BtcECKey.fromPrivate(b);

        // Valid PubKey, invalid rskAddress
        FederationMember invalidRskPubKey = new FederationMember(
            federationMember.getBtcPublicKey(),
            invalidRskKey,
            federationMember.getMstPublicKey()
        );
        assertFalse(federation.isMember(invalidRskPubKey));

        //Invalid PubKey, valid rskAddress
        FederationMember invalidBtcPubKey = new FederationMember(
            invalidBtcKey,
            federationMember.getRskPublicKey(),
            federationMember.getMstPublicKey()
        );
        assertFalse(federation.isMember(invalidBtcPubKey));

        //Valid btcKey & valid rskAddress, invalid mstKey
        FederationMember invalidMstPubKey = new FederationMember(
            federationMember.getBtcPublicKey(),
            federationMember.getRskPublicKey(),
            invalidRskKey
        );
        assertFalse(federation.isMember(invalidMstPubKey));

        //All invalid params
        FederationMember invalidPubKeys = new FederationMember(invalidBtcKey, invalidRskKey, invalidRskKey);
        assertFalse(federation.isMember(invalidPubKeys));
    }
}
