/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.aeron.common.BitUtil;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.driver.MediaDriver;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class PongTest
{
    public static final String PING_URI = "udp://localhost:54325";
    public static final String PONG_URI = "udp://localhost:54326";

    private static final int PING_STREAM_ID = 1;
    private static final int PONG_STREAM_ID = 2;

    private final MediaDriver.Context context = new MediaDriver.Context();
    private final Aeron.Context pingAeronContext = new Aeron.Context();
    private final Aeron.Context pongAeronContext = new Aeron.Context();

    private Aeron pingClient;
    private Aeron pongClient;
    private MediaDriver driver;
    private Subscription pingSubscription;
    private Subscription pongSubscription;
    private Publication pingPublication;
    private Publication pongPublication;

    private AtomicBuffer buffer = new AtomicBuffer(new byte[4096]);
    private DataHandler pongHandler = mock(DataHandler.class);

    @Before
    public void setup() throws Exception
    {
        context.dirsDeleteOnExit(true);

        driver = MediaDriver.launch(context);
        pingClient = Aeron.connect(pingAeronContext);
        pongClient = Aeron.connect(pongAeronContext);

        pingPublication = pingClient.addPublication(PING_URI, PING_STREAM_ID);
        pingSubscription = pongClient.addSubscription(PING_URI, PING_STREAM_ID, this::pingHandler);

        pongPublication = pongClient.addPublication(PONG_URI, PONG_STREAM_ID);
        pongSubscription = pingClient.addSubscription(PONG_URI, PONG_STREAM_ID, pongHandler);
    }

    @After
    public void closeEverything() throws Exception
    {
        if (null != pingPublication)
        {
            pingPublication.close();
        }

        if (null != pongPublication)
        {
            pongPublication.close();
        }

        if (null != pingSubscription)
        {
            pingSubscription.close();
        }

        if (null != pongSubscription)
        {
            pongSubscription.close();
        }

        pongClient.close();
        pingClient.close();
        driver.close();
    }

    @Test
    public void playPingPong()
    {
        buffer.putInt(0, 1);

        assertTrue(pingPublication.offer(buffer, 0, BitUtil.SIZE_OF_INT));

        final int fragmentsRead[] = new int[1];

        SystemTestHelper.executeUntil(
            () -> fragmentsRead[0] > 0,
            (i) ->
            {
                fragmentsRead[0] += pingSubscription.poll(10);
                Thread.yield();
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(900));

        fragmentsRead[0] = 0;

        SystemTestHelper.executeUntil(
            () -> fragmentsRead[0] > 0,
            (i) ->
            {
                fragmentsRead[0] += pongSubscription.poll(10);
                Thread.yield();
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(900));

        verify(pongHandler).onData(
            anyObject(),
            eq(DataHeaderFlyweight.HEADER_LENGTH),
            eq(BitUtil.SIZE_OF_INT),
            anyInt(),
            eq((byte)DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
    }

    public void pingHandler(AtomicBuffer buffer, int offset, int length, int sessionId, byte flags)
    {
        // echoes back the ping
        assertTrue(pongPublication.offer(buffer, offset, length));
    }
}