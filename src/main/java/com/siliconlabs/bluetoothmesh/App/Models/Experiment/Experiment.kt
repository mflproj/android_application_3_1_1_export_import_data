/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models.Experiment

import androidx.annotation.StringRes
import com.siliconlabs.bluetoothmesh.App.Utils.Converters
import com.siliconlabs.bluetoothmesh.R

enum class Experiment(val maxTime: Long,
                      @StringRes val titleRes: Int,
                      @StringRes val descriptionRes: Int,
                      val node: Node? = null) {
    SCAN_NODE_UUID_0E_0F(1500, R.string.test_title_beaconing, R.string.test_desc_beaconing_mode_low_latency, Node.PROXY),
    SCAN_NODE_UUID_0E_1F(2500, R.string.test_title_beaconing, R.string.test_desc_beaconing_mode_low_latency, Node.RELAY),
    SCAN_NODE_UUID_0E_2F(4000, R.string.test_title_beaconing, R.string.test_desc_beaconing_mode_balanced, Node.FRIEND),
    SCAN_NODE_UUID_0E_3F(5500, R.string.test_title_beaconing, R.string.test_desc_beaconing_mode_low_power, Node.LPN),

    PROVISIONING_NODE_UUID_0E_0F(10000, R.string.test_title_provisioning, R.string.test_desc_provisioning_no_oob, Node.PROXY),
    PROVISIONING_NODE_UUID_0E_1F(10000, R.string.test_title_provisioning, R.string.test_desc_provisioning_static_oob, Node.RELAY),
    PROVISIONING_NODE_UUID_0E_2F(30000, R.string.test_title_provisioning, R.string.test_desc_provisioning_output_oob, Node.FRIEND),
    PROVISIONING_NODE_UUID_0E_3F(30000, R.string.test_title_provisioning, R.string.test_desc_provisioning_input_oob, Node.LPN),

    CONTROL_NODE_UUID_0E_0F_WITH_ACK(400, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_proxy_ack, Node.PROXY),
    CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK(180, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_proxy_no_ack, Node.PROXY),
    CONTROL_NODE_UUID_0E_1F_WITH_ACK(450, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_relay_ack, Node.RELAY),
    CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK(180, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_relay_no_ack, Node.RELAY),
    CONTROL_NODE_UUID_0E_2F_WITH_ACK(450, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_friend_ack, Node.FRIEND),
    CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK(180, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_friend_no_ack, Node.FRIEND),
    CONTROL_NODE_UUID_0E_3F_WITH_ACK(5000, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_lpn_ack, Node.LPN),
    CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK(180, R.string.test_title_unicast_control, R.string.test_desc_unicast_control_lpn_no_ack, Node.LPN),

    CONTROL_GROUP(180, R.string.test_title_multicast_control, R.string.test_desc_multicast_control),

    REMOVE_NODES_IN_NETWORK(60000, R.string.test_title_remove_node, R.string.test_desc_remove_node),
    ADD_NODES_TO_NETWORK(60000, R.string.test_title_add_node, R.string.test_desc_add_node),

    CONNECTION_NETWORK(30000, R.string.test_title_connection, R.string.test_desc_connection),
    POST_TESTING(60000, R.string.test_title_post_testing, R.string.test_desc_remove_all_node);

    fun getNext(): Experiment? {
        return values().runCatching { get(ordinal + 1) }.getOrNull()
    }

    fun getAllIncompleteUsingSameNode(): List<Experiment> {
        return node?.uuid?.let { thisNodeUuid ->
            values().filter { it.node?.uuid == thisNodeUuid }
                    .filter { it.ordinal >= ordinal }
        } ?: emptyList()
    }

    enum class Node(val uuid: String) {
        PROXY("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F"),
        RELAY("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 1F"),
        FRIEND("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 2F"),
        LPN("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 3F");

        fun isUuidMatching(uuid: ByteArray) = this.uuid == Converters.getHexValue(uuid).trim()
    }
}
