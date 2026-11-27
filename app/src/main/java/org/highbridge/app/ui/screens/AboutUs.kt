// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.highbridge.app.BuildConfig
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.tr

/** The people behind Highbridge, and where to find them. Text comes from stpaulradioauthority.com and stpaulmesh.com. */
object Community {
    const val RADIO_AUTHORITY = "https://stpaulradioauthority.com"
    const val MESH = "https://stpaulmesh.com"
    const val CONTACT = "https://stpaulradioauthority.com/contact"
    const val MEETUPS = "https://stpaulmesh.com/meetups"
    // Radio Authority chats (all kinds of radio) and St. Paul Mesh chats (mesh radio).
    const val RA_DISCORD = "https://discord.gg/ZHWdrBVr5"
    const val RA_SIGNAL = "https://signal.group/#CjQKIFgc_jdWjYCqFjrfk7fpx35_TmBtA6tTVe5HkRYJ3c4REhCHG1_JWTFlZ64zdtP3738F"
    const val RA_FACEBOOK = "https://www.facebook.com/share/g/1EMdJHXuLC/"
    const val MESH_DISCORD = "https://discord.gg/ECwsgSSDR"
    const val MESH_SIGNAL = "https://signal.group/#CjQKIN0aJBnhfrDVcFrNuYJxSgsuV2R4-qvnOYhiSgsgvlUsEhDestxxy_Ptbje1N4q_jUtY"
    const val MESH_FACEBOOK = "https://www.facebook.com/share/g/1EmfCsGDnE/"

    /** Radios we use and recommend; Amazon affiliate links, the same picks as the websites' How to help pages. */
    data class Pick(val name: String, val blurb: String, val url: String)
    val HANDHELDS = listOf(
        Pick("Meshnology Heltec V4 kit with GPS, battery and case", "Heltec V4 board, GPS, 3000 mAh battery, antenna and case. Plugs together, no soldering.", "https://link.amazon/B00jNqw9H"),
        Pick("Meshnology Heltec V4 Meshtastic node", "ESP32-S3 Heltec V4 with Bluetooth, ready for Meshtastic or MeshCore.", "https://link.amazon/B0fVvoGEC"),
        Pick("Meshnology Heltec V4 kit with 3000 mAh battery", "Board, battery, antenna and case in one box. A good first handheld.", "https://link.amazon/B06vBuQcY"),
        Pick("Meshnology Heltec V4 kit, Bluetooth with 3000 mAh battery", "Another Meshnology bundle with battery and case for a pocket node.", "https://link.amazon/B0gHn0oGo"),
    )
    val SOLAR = listOf(
        Pick("Elecrow ThinkNode M6 outdoor solar node", "Weatherproof (IP65) Meshtastic node with a built-in 6 W solar panel and batteries. Mount it and go.", "https://link.amazon/B00pg3vbM"),
        Pick("KEEPTEEN MeshCore solar repeater", "Self-contained solar repeater that ships set up for MeshCore. Made to live outdoors.", "https://link.amazon/B0iq5UtKv"),
        Pick("MeshCore solar repeater (Meshtastic compatible)", "All-in-one solar repeater for a roof or pole. Runs MeshCore or Meshtastic.", "https://link.amazon/B0gZmfcHj"),
    )

    /** Tips. Hidden in the Google Play build, whose payments policy doesn't allow them for groups that aren't tax-exempt. */
    data class Tip(val service: String, val handle: String, val url: String, val note: String)
    val TIPS = listOf(
        Tip("PayPal", "paypal.me/thefoofer", "https://www.paypal.me/thefoofer", "Works with a PayPal balance, bank account or card."),
        Tip("Venmo", "@kabifff", "https://venmo.com/u/kabifff", "Check the spelling: kabifff has three f's."),
        Tip("Cash App", "\$kabifff", "https://cash.app/\$kabifff", "Check the spelling: kabifff has three f's."),
    )
    val showTips: Boolean get() = BuildConfig.SHOW_TIPS
}

private fun Context.openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

/** A sentence with one tappable link in it: [before][link][after]. */
@Composable
private fun LinkLine(before: String, link: String, url: String, after: String = "", bold: Boolean = false) {
    val color = MaterialTheme.colorScheme.primary
    Text(buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)))) { append(link) }
        append(after)
    }, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatButtons(discord: String, signal: String, facebook: String) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { ctx.openUrl(discord) }) { Text("Discord") }
        OutlinedButton(onClick = { ctx.openUrl(signal) }) { Text("Signal") }
        OutlinedButton(onClick = { ctx.openUrl(facebook) }) { Text("Facebook") }
    }
}

/** About us: the St. Paul Radio Authority, its companion site stpaulmesh.com, and how to reach people. */
@Composable
fun AboutUsScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader(tr("About us"), tr("The people behind Highbridge"), onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HbCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkLine(tr("Brought to you by the") + " ", "St. Paul Radio Authority", Community.RADIO_AUTHORITY, bold = true)
                LinkLine(tr("Check out our companion website,") + " ", "stpaulmesh.com", Community.MESH)
            } }

            SectionLabel("St. Paul Radio Authority", Modifier.padding(0.dp))
            Text(tr("The St. Paul Radio Authority helps the people of Saint Paul connect with each other by radio, with no internet, cell service or monthly plan required."))
            Text(tr("When the power is out, the cell network is down, or you simply want a way to reach your neighbors that no company controls, radio still works."))
            Text(tr("We are a community resource, run by neighbors for neighbors. We share plain-English guides, local channels and repeaters, and the rules that keep the airwaves friendly and legal."))
            Text(tr("You do not need to be technical to take part. Whether you have never held a radio, just bought your first one, or have been licensed for decades, there is a place for you. No question is too basic, and everyone started somewhere."))
            LinkLine(tr("Ham radio, GMRS, FRS and mesh guides, local repeaters and FCC rules are at") + " ", "stpaulradioauthority.com", Community.RADIO_AUTHORITY, ".")

            SectionLabel("St. Paul Mesh", Modifier.padding(0.dp))
            Text(tr("Saint Paul's neighborhood mesh: small, low-cost radios that pass text messages from neighbor to neighbor across the city, with no cell towers, no internet and no monthly bill."))
            Text(tr("It is text only, it is slow, and it will never replace your phone plan. What it gives you is a way to reach your neighbors that keeps working when the power is out or the cell network is jammed."))
            LinkLine(tr("Local settings, Meshtastic and MeshCore guides, the node map and repeater tips are at") + " ", "stpaulmesh.com", Community.MESH, ".")

            SectionLabel(tr("Get help or say hello"), Modifier.padding(0.dp))
            Text(tr("Stuck on a setup step, not sure which radio to buy, or just want to say hello? Local operators are happy to help you get on the air."))
            Text(tr("All kinds of radio (St. Paul Radio Authority)"), fontWeight = FontWeight.Bold)
            ChatButtons(Community.RA_DISCORD, Community.RA_SIGNAL, Community.RA_FACEBOOK)
            Text(tr("Mesh radio (St. Paul Mesh)"), fontWeight = FontWeight.Bold)
            ChatButtons(Community.MESH_DISCORD, Community.MESH_SIGNAL, Community.MESH_FACEBOOK)
            Row2 {
                TextButton(onClick = { ctx.openUrl(Community.MEETUPS) }) { Text(tr("Meetups")) }
                TextButton(onClick = { ctx.openUrl(Community.CONTACT) }) { Text(tr("Contact us")) }
            }

            SectionLabel("Highbridge", Modifier.padding(0.dp))
            Text(tr("Highbridge is named for Saint Paul's High Bridge, which joins West 7th to the West Side bluffs."))
            Text(tr("It is free software with no ads and no tracking, made in Saint Paul."))
            Button(onClick = { nav.navigate("help") }, Modifier.fillMaxWidth()) { Text(tr("How you can help")) }
            org.highbridge.app.ui.Gap(16)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row2(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }

/** How to help: affiliate radio picks, tips (outside the Play build) and the other ways to pitch in, from both websites. */
@Composable
fun HowToHelpScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader(tr("How to help"), tr("Spreading radio joy to the folks of Saint Paul"), onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HbCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(if (Community.showTips)
                    "Highbridge is distributed freely and without ads, but developing, maintaining and improving it has a lot of related costs. Make your next radio purchase through one of our affiliate links, or send us a tip on PayPal, Venmo or Cash App."
                else "Highbridge is distributed freely and without ads, but developing, maintaining and improving it has a lot of related costs. Make your next radio purchase through one of our affiliate links."))
                if (Community.showTips) Text(tr("Tips help pay for the app and for community radios: repeaters anyone can use, and handheld nodes given to neighbors at no charge."), fontSize = 13.sp)
            } }

            SectionLabel(tr("Buy your next radio through us"), Modifier.padding(0.dp))
            Text(tr("These are the radios we use. Buying through these links costs you nothing extra and helps fund community radios."), fontSize = 13.sp)
            Text(tr("Handheld nodes (plug together, no soldering)"), fontWeight = FontWeight.Bold)
            Community.HANDHELDS.forEach { PickRow(it) }
            Text(tr("Stationary solar nodes and repeaters"), fontWeight = FontWeight.Bold)
            Community.SOLAR.forEach { PickRow(it) }
            Text(tr("As an Amazon Associate we earn from qualifying purchases."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (Community.showTips) {
                SectionLabel(tr("Send a tip"), Modifier.padding(0.dp))
                Community.TIPS.forEach { t ->
                    HbCard { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.service, fontWeight = FontWeight.Bold)
                        Text(t.handle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(tr(t.note), fontSize = 12.sp)
                        Button(onClick = { ctx.openUrl(t.url) }) { Text(tr("Open") + " " + t.service) }
                    } }
                }
                Text(tr("We're working on forming a nonprofit but aren't one yet, so tips aren't tax-deductible. Receipts for radio equipment are gladly provided on request. Payments go through PayPal, Venmo or Cash App directly; Highbridge never sees your card or bank details."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionLabel(tr("Other ways to help"), Modifier.padding(0.dp))
            HelpItem(tr("Share both websites far and wide"),
                tr("Tell friends, neighbors, your block club and your group chats about stpaulradioauthority.com and stpaulmesh.com. The more people who know, the bigger the network grows.")) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        "Radio for every neighbor in Saint Paul: ${Community.RADIO_AUTHORITY} and ${Community.MESH}"), null))
                }) { Text(tr("Share")) }
            }
            HelpItem(tr("Invite your friends to our chats"), tr("Bring people into the conversation, where we answer questions, plan meetups and help with setup.")) {
                ChatButtons(Community.MESH_DISCORD, Community.MESH_SIGNAL, Community.MESH_FACEBOOK)
            }
            HelpItem(tr("Host a meetup in your basement"), tr("Have a basement, garage or back room that fits a few folding chairs? Host a small meetup where neighbors can set up radios together.")) {
                TextButton(onClick = { ctx.openUrl(Community.CONTACT) }) { Text(tr("Let us know")) }
            }
            HelpItem(tr("Find a roof for a solar node"), tr("A solar node up high can cover a whole neighborhood. If you own or manage a building, or know someone who does, especially on high ground, get in touch. Nodes are small, run on sunlight and need no wiring.")) {
                TextButton(onClick = { ctx.openUrl(Community.CONTACT) }) { Text(tr("Get in touch")) }
            }
            HelpItem(tr("Use your 3D printer to print cases"), tr("Handheld and solar nodes need enclosures. If you have a 3D printer and a little time, tell us and we'll send files and sizes.")) {
                TextButton(onClick = { ctx.openUrl(Community.CONTACT) }) { Text(tr("Tell us")) }
            }
            HelpItem(tr("Hand out mesh radio zines"), tr("Leave zines about mesh radio at coffee shops, libraries, little free libraries and community boards. Ask us for a stack.")) {
                TextButton(onClick = { ctx.openUrl(Community.CONTACT) }) { Text(tr("Ask for zines")) }
            }
            HelpItem(tr("Set up a radio and keep it on"), tr("Every radio you add makes the network stronger for everyone. Leave yours on, or put up a solar node.")) {}
            org.highbridge.app.ui.Gap(16)
        }
    }
}

@Composable
private fun PickRow(p: Community.Pick) {
    val ctx = LocalContext.current
    HbCard { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(p.name, fontWeight = FontWeight.Bold)
        Text(tr(p.blurb), fontSize = 13.sp)
        TextButton(onClick = { ctx.openUrl(p.url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(tr("See on Amazon") + " →") }
    } }
}

@Composable
private fun HelpItem(title: String, body: String, action: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, fontSize = 13.sp)
        action()
    }
}
