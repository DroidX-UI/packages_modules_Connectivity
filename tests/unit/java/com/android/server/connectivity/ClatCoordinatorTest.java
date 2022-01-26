/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.net.INetd.IF_STATE_UP;

import static com.android.net.module.util.NetworkStackConstants.ETHER_MTU;
import static com.android.server.connectivity.ClatCoordinator.CLAT_MAX_MTU;
import static com.android.server.connectivity.ClatCoordinator.INIT_V4ADDR_PREFIX_LEN;
import static com.android.server.connectivity.ClatCoordinator.INIT_V4ADDR_STRING;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import android.annotation.NonNull;
import android.net.INetd;
import android.net.IpPrefix;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class ClatCoordinatorTest {
    private static final String BASE_IFACE = "test0";
    private static final String STACKED_IFACE = "v4-test0";
    private static final int BASE_IFINDEX = 1000;

    private static final IpPrefix NAT64_IP_PREFIX = new IpPrefix("64:ff9b::/96");
    private static final String NAT64_PREFIX_STRING = "64:ff9b::";
    private static final int GOOGLE_DNS_4 = 0x08080808;  // 8.8.8.8
    private static final int NETID = 42;

    // The test fwmark means: PERMISSION_SYSTEM (0x2), protectedFromVpn: true,
    // explicitlySelected: true, netid: 42. For bit field structure definition, see union Fwmark in
    // system/netd/include/Fwmark.h
    private static final int MARK = 0xb002a;

    private static final String XLAT_LOCAL_IPV4ADDR_STRING = "192.0.0.46";
    private static final String XLAT_LOCAL_IPV6ADDR_STRING = "2001:db8:0:b11::464";
    private static final int CLATD_PID = 10483;

    private static final int TUN_FD = 534;
    private static final int RAW_SOCK_FD = 535;
    private static final int PACKET_SOCK_FD = 536;
    private static final ParcelFileDescriptor TUN_PFD = new ParcelFileDescriptor(
            new FileDescriptor());
    private static final ParcelFileDescriptor RAW_SOCK_PFD = new ParcelFileDescriptor(
            new FileDescriptor());
    private static final ParcelFileDescriptor PACKET_SOCK_PFD = new ParcelFileDescriptor(
            new FileDescriptor());

    @Mock private INetd mNetd;
    @Spy private TestDependencies mDeps = new TestDependencies();

    /**
      * The dependency injection class is used to mock the JNI functions and system functions
      * for clatd coordinator control plane. Note that any testing used JNI functions need to
      * be overridden to avoid calling native methods.
      */
    protected class TestDependencies extends ClatCoordinator.Dependencies {
        /**
          * Get netd.
          */
        @Override
        public INetd getNetd() {
            return mNetd;
        }

        /**
         * @see ParcelFileDescriptor#adoptFd(int).
         */
        @Override
        public ParcelFileDescriptor adoptFd(int fd) {
            switch (fd) {
                case TUN_FD:
                    return TUN_PFD;
                case RAW_SOCK_FD:
                    return RAW_SOCK_PFD;
                case PACKET_SOCK_FD:
                    return PACKET_SOCK_PFD;
                default:
                    fail("unsupported arg: " + fd);
                    return null;
            }
        }

        /**
         * Get interface index for a given interface.
         */
        @Override
        public int getInterfaceIndex(String ifName) {
            if (BASE_IFACE.equals(ifName)) {
                return BASE_IFINDEX;
            }
            fail("unsupported arg: " + ifName);
            return -1;
        }

        /**
         * Create tun interface for a given interface name.
         */
        @Override
        public int createTunInterface(@NonNull String tuniface) throws IOException {
            if (STACKED_IFACE.equals(tuniface)) {
                return TUN_FD;
            }
            fail("unsupported arg: " + tuniface);
            return -1;
        }

        /**
         * Pick an IPv4 address for clat.
         */
        @Override
        public String selectIpv4Address(@NonNull String v4addr, int prefixlen)
                throws IOException {
            if (INIT_V4ADDR_STRING.equals(v4addr) && INIT_V4ADDR_PREFIX_LEN == prefixlen) {
                return XLAT_LOCAL_IPV4ADDR_STRING;
            }
            fail("unsupported args: " + v4addr + ", " + prefixlen);
            return null;
        }

        /**
         * Generate a checksum-neutral IID.
         */
        @Override
        public String generateIpv6Address(@NonNull String iface, @NonNull String v4,
                @NonNull String prefix64) throws IOException {
            if (BASE_IFACE.equals(iface) && XLAT_LOCAL_IPV4ADDR_STRING.equals(v4)
                    && NAT64_PREFIX_STRING.equals(prefix64)) {
                return XLAT_LOCAL_IPV6ADDR_STRING;
            }
            fail("unsupported args: " + iface + ", " + v4 + ", " + prefix64);
            return null;
        }

        /**
         * Detect MTU.
         */
        @Override
        public int detectMtu(@NonNull String platSubnet, int platSuffix, int mark)
                throws IOException {
            if (NAT64_PREFIX_STRING.equals(platSubnet) && GOOGLE_DNS_4 == platSuffix
                    && MARK == mark) {
                return ETHER_MTU;
            }
            fail("unsupported args: " + platSubnet + ", " + platSuffix + ", " + mark);
            return -1;
        }

        /**
         * Open IPv6 raw socket and set SO_MARK.
         */
        @Override
        public int openRawSocket6(int mark) throws IOException {
            if (mark == MARK) {
                return RAW_SOCK_FD;
            }
            fail("unsupported arg: " + mark);
            return -1;
        }

        /**
         * Open packet socket.
         */
        @Override
        public int openPacketSocket() throws IOException {
            // assume that open socket always successfully because there is no argument to check.
            return PACKET_SOCK_FD;
        }

        /**
         * Add anycast setsockopt.
         */
        @Override
        public void addAnycastSetsockopt(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            if (Objects.equals(RAW_SOCK_PFD.getFileDescriptor(), sock)
                    && XLAT_LOCAL_IPV6ADDR_STRING.equals(v6)
                    && BASE_IFINDEX == ifindex) return;
            fail("unsupported args: " + sock + ", " + v6 + ", " + ifindex);
        }

        /**
         * Configure packet socket.
         */
        @Override
        public void configurePacketSocket(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            if (Objects.equals(PACKET_SOCK_PFD.getFileDescriptor(), sock)
                    && XLAT_LOCAL_IPV6ADDR_STRING.equals(v6)
                    && BASE_IFINDEX == ifindex) return;
            fail("unsupported args: " + sock + ", " + v6 + ", " + ifindex);
        }

        /**
         * Start clatd.
         */
        @Override
        public int startClatd(@NonNull FileDescriptor tunfd, @NonNull FileDescriptor readsock6,
                @NonNull FileDescriptor writesock6, @NonNull String iface, @NonNull String pfx96,
                @NonNull String v4, @NonNull String v6) throws IOException {
            if (Objects.equals(TUN_PFD.getFileDescriptor(), tunfd)
                    && Objects.equals(PACKET_SOCK_PFD.getFileDescriptor(), readsock6)
                    && Objects.equals(RAW_SOCK_PFD.getFileDescriptor(), writesock6)
                    && BASE_IFACE.equals(iface)
                    && NAT64_PREFIX_STRING.equals(pfx96)
                    && XLAT_LOCAL_IPV4ADDR_STRING.equals(v4)
                    && XLAT_LOCAL_IPV6ADDR_STRING.equals(v6)) {
                return CLATD_PID;
            }
            fail("unsupported args: " + tunfd + ", " + readsock6 + ", " + writesock6 + ", "
                    + ", " + iface + ", " + v4 + ", " + v6);
            return -1;
        }

        /**
         * Stop clatd.
         */
        public void stopClatd(@NonNull String iface, @NonNull String pfx96, @NonNull String v4,
                @NonNull String v6, int pid) throws IOException {
            if (pid == -1) {
                fail("unsupported arg: " + pid);
            }
        }
    };

    @NonNull
    private ClatCoordinator makeClatCoordinator() throws Exception {
        final ClatCoordinator coordinator = new ClatCoordinator(mDeps);
        return coordinator;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    private boolean assertContainsFlag(String[] flags, String match) {
        for (String flag : flags) {
            if (flag.equals(match)) return true;
        }
        fail("Missing flag: " + match);
        return false;
    }

    @Test
    public void testStartStopClatd() throws Exception {
        final ClatCoordinator coordinator = makeClatCoordinator();
        final InOrder inOrder = inOrder(mNetd, mDeps);
        clearInvocations(mNetd, mDeps);

        // [1] Start clatd.
        final String addr6For464xlat = coordinator.clatStart(BASE_IFACE, NETID, NAT64_IP_PREFIX);
        assertEquals(XLAT_LOCAL_IPV6ADDR_STRING, addr6For464xlat);

        // Pick an IPv4 address.
        inOrder.verify(mDeps).selectIpv4Address(eq(INIT_V4ADDR_STRING),
                eq(INIT_V4ADDR_PREFIX_LEN));

        // Generate a checksum-neutral IID.
        inOrder.verify(mDeps).generateIpv6Address(eq(BASE_IFACE),
                eq(XLAT_LOCAL_IPV4ADDR_STRING), eq(NAT64_PREFIX_STRING));

        // Open, configure and bring up the tun interface.
        inOrder.verify(mDeps).createTunInterface(eq(STACKED_IFACE));
        inOrder.verify(mDeps).adoptFd(eq(TUN_FD));
        inOrder.verify(mNetd).interfaceSetEnableIPv6(eq(STACKED_IFACE), eq(false /* enable */));
        inOrder.verify(mDeps).detectMtu(eq(NAT64_PREFIX_STRING), eq(GOOGLE_DNS_4), eq(MARK));
        inOrder.verify(mNetd).interfaceSetMtu(eq(STACKED_IFACE),
                eq(1472 /* ETHER_MTU(1500) - MTU_DELTA(28) */));
        inOrder.verify(mNetd).interfaceSetCfg(argThat(cfg ->
                STACKED_IFACE.equals(cfg.ifName)
                && XLAT_LOCAL_IPV4ADDR_STRING.equals(cfg.ipv4Addr)
                && (32 == cfg.prefixLength)
                && "".equals(cfg.hwAddr)
                && assertContainsFlag(cfg.flags, IF_STATE_UP)));

        // Open and configure 464xlat read/write sockets.
        inOrder.verify(mDeps).openPacketSocket();
        inOrder.verify(mDeps).adoptFd(eq(PACKET_SOCK_FD));
        inOrder.verify(mDeps).openRawSocket6(eq(MARK));
        inOrder.verify(mDeps).adoptFd(eq(RAW_SOCK_FD));
        inOrder.verify(mDeps).getInterfaceIndex(eq(BASE_IFACE));
        inOrder.verify(mDeps).addAnycastSetsockopt(
                argThat(fd -> Objects.equals(RAW_SOCK_PFD.getFileDescriptor(), fd)),
                eq(XLAT_LOCAL_IPV6ADDR_STRING), eq(BASE_IFINDEX));
        inOrder.verify(mDeps).configurePacketSocket(
                argThat(fd -> Objects.equals(PACKET_SOCK_PFD.getFileDescriptor(), fd)),
                eq(XLAT_LOCAL_IPV6ADDR_STRING), eq(BASE_IFINDEX));

        // Start clatd.
        inOrder.verify(mDeps).startClatd(
                argThat(fd -> Objects.equals(TUN_PFD.getFileDescriptor(), fd)),
                argThat(fd -> Objects.equals(PACKET_SOCK_PFD.getFileDescriptor(), fd)),
                argThat(fd -> Objects.equals(RAW_SOCK_PFD.getFileDescriptor(), fd)),
                eq(BASE_IFACE), eq(NAT64_PREFIX_STRING),
                eq(XLAT_LOCAL_IPV4ADDR_STRING), eq(XLAT_LOCAL_IPV6ADDR_STRING));
        inOrder.verifyNoMoreInteractions();

        // [2] Start clatd again failed.
        assertThrows("java.io.IOException: Clatd has started on test0 (pid 10483)",
                IOException.class,
                () -> coordinator.clatStart(BASE_IFACE, NETID, NAT64_IP_PREFIX));

        // [3] Expect clatd to stop successfully.
        coordinator.clatStop();
        inOrder.verify(mDeps).stopClatd(eq(BASE_IFACE), eq(NAT64_PREFIX_STRING),
                eq(XLAT_LOCAL_IPV4ADDR_STRING), eq(XLAT_LOCAL_IPV6ADDR_STRING), eq(CLATD_PID));
        inOrder.verifyNoMoreInteractions();

        // [4] Expect an IO exception while stopping a clatd that doesn't exist.
        assertThrows("java.io.IOException: Clatd has not started", IOException.class,
                () -> coordinator.clatStop());
        inOrder.verify(mDeps, never()).stopClatd(anyString(), anyString(), anyString(),
                anyString(), anyInt());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testGetFwmark() throws Exception {
        assertEquals(0xb0064, ClatCoordinator.getFwmark(100));
        assertEquals(0xb03e8, ClatCoordinator.getFwmark(1000));
        assertEquals(0xb2710, ClatCoordinator.getFwmark(10000));
        assertEquals(0xbffff, ClatCoordinator.getFwmark(65535));
    }

    @Test
    public void testAdjustMtu() throws Exception {
        // Expected mtu is that IPV6_MIN_MTU(1280) minus MTU_DELTA(28).
        assertEquals(1252, ClatCoordinator.adjustMtu(-1 /* detect mtu failed */));
        assertEquals(1252, ClatCoordinator.adjustMtu(500));
        assertEquals(1252, ClatCoordinator.adjustMtu(1000));
        assertEquals(1252, ClatCoordinator.adjustMtu(1280));

        // Expected mtu is that the detected mtu minus MTU_DELTA(28).
        assertEquals(1372, ClatCoordinator.adjustMtu(1400));
        assertEquals(1472, ClatCoordinator.adjustMtu(ETHER_MTU));
        assertEquals(65508, ClatCoordinator.adjustMtu(CLAT_MAX_MTU));

        // Expected mtu is that CLAT_MAX_MTU(65536) minus MTU_DELTA(28).
        assertEquals(65508, ClatCoordinator.adjustMtu(CLAT_MAX_MTU + 1 /* over maximum mtu */));
    }
}