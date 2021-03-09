/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.bot.spring;

import static java.util.Collections.singletonList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.google.common.io.ByteStreams;

import com.linecorp.bot.client.LineBlobClient;
import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.action.DatetimePickerAction;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.action.PostbackAction;
import com.linecorp.bot.model.action.URIAction;
import com.linecorp.bot.model.event.BeaconEvent;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.JoinEvent;
import com.linecorp.bot.model.event.MemberJoinedEvent;
import com.linecorp.bot.model.event.MemberLeftEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.PostbackEvent;
import com.linecorp.bot.model.event.UnfollowEvent;
import com.linecorp.bot.model.event.UnknownEvent;
import com.linecorp.bot.model.event.UnsendEvent;
import com.linecorp.bot.model.event.VideoPlayCompleteEvent;
import com.linecorp.bot.model.event.message.AudioMessageContent;
import com.linecorp.bot.model.event.message.ContentProvider;
import com.linecorp.bot.model.event.message.FileMessageContent;
import com.linecorp.bot.model.event.message.ImageMessageContent;
import com.linecorp.bot.model.event.message.LocationMessageContent;
import com.linecorp.bot.model.event.message.StickerMessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import com.linecorp.bot.model.event.message.VideoMessageContent;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.group.GroupMemberCountResponse;
import com.linecorp.bot.model.group.GroupSummaryResponse;
import com.linecorp.bot.model.message.AudioMessage;
import com.linecorp.bot.model.message.ImageMessage;
import com.linecorp.bot.model.message.ImagemapMessage;
import com.linecorp.bot.model.message.LocationMessage;
import com.linecorp.bot.model.message.Message;
import com.linecorp.bot.model.message.StickerMessage;
import com.linecorp.bot.model.message.TemplateMessage;
import com.linecorp.bot.model.message.TextMessage;
import com.linecorp.bot.model.message.VideoMessage;
import com.linecorp.bot.model.message.imagemap.ImagemapArea;
import com.linecorp.bot.model.message.imagemap.ImagemapBaseSize;
import com.linecorp.bot.model.message.imagemap.ImagemapExternalLink;
import com.linecorp.bot.model.message.imagemap.ImagemapVideo;
import com.linecorp.bot.model.message.imagemap.MessageImagemapAction;
import com.linecorp.bot.model.message.imagemap.URIImagemapAction;
import com.linecorp.bot.model.message.sender.Sender;
import com.linecorp.bot.model.message.template.ButtonsTemplate;
import com.linecorp.bot.model.message.template.CarouselColumn;
import com.linecorp.bot.model.message.template.CarouselTemplate;
import com.linecorp.bot.model.message.template.ConfirmTemplate;
import com.linecorp.bot.model.message.template.ImageCarouselColumn;
import com.linecorp.bot.model.message.template.ImageCarouselTemplate;
import com.linecorp.bot.model.response.BotApiResponse;
import com.linecorp.bot.model.room.RoomMemberCountResponse;
import com.linecorp.bot.spring.boot.annotation.EventMapping;
import com.linecorp.bot.spring.boot.annotation.LineMessageHandler;

import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@LineMessageHandler
public class KitchenSinkController {
    @Autowired
    private LineMessagingClient lineMessagingClient;

    @Autowired
    private LineBlobClient lineBlobClient;

    @EventMapping
    public void handleTextMessageEvent(MessageEvent<TextMessageContent> event) throws Exception {
        TextMessageContent message = event.getMessage();
        handleTextContent(event.getReplyToken(), event, message);
    }

    @EventMapping
    public void handleStickerMessageEvent(MessageEvent<StickerMessageContent> event) {
        handleSticker(event.getReplyToken(), event.getMessage());
    }

    @EventMapping
    public void handleLocationMessageEvent(MessageEvent<LocationMessageContent> event) {
        LocationMessageContent locationMessage = event.getMessage();
        reply(event.getReplyToken(), new LocationMessage(
                locationMessage.getTitle(),
                locationMessage.getAddress(),
                locationMessage.getLatitude(),
                locationMessage.getLongitude()
        ));
    }

    @EventMapping
    public void handleImageMessageEvent(MessageEvent<ImageMessageContent> event) throws IOException {
        // You need to install ImageMagick
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent jpg;
                    final DownloadedContent previewImg;
                    if (provider.isExternal()) {
                        jpg = new DownloadedContent(null, provider.getOriginalContentUrl());
                        previewImg = new DownloadedContent(null, provider.getPreviewImageUrl());
                    } else {
                        jpg = saveContent("jpg", responseBody);
                        previewImg = createTempFile("jpg");
                        system(
                                "convert",
                                "-resize", "240x",
                                jpg.path.toString(),
                                previewImg.path.toString());
                    }
                    reply(event.getReplyToken(),
                          new ImageMessage(jpg.getUri(), previewImg.getUri()));
                });
    }

    @EventMapping
    public void handleAudioMessageEvent(MessageEvent<AudioMessageContent> event) throws IOException {
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent mp4;
                    if (provider.isExternal()) {
                        mp4 = new DownloadedContent(null, provider.getOriginalContentUrl());
                    } else {
                        mp4 = saveContent("mp4", responseBody);
                    }
                    reply(event.getReplyToken(), new AudioMessage(mp4.getUri(), 100));
                });
    }

    @EventMapping
    public void handleVideoMessageEvent(MessageEvent<VideoMessageContent> event) throws IOException {
        log.info("Got video message: duration={}ms", event.getMessage().getDuration());

        // You need to install ffmpeg and ImageMagick.
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    final ContentProvider provider = event.getMessage().getContentProvider();
                    final DownloadedContent mp4;
                    final DownloadedContent previewImg;
                    if (provider.isExternal()) {
                        mp4 = new DownloadedContent(null, provider.getOriginalContentUrl());
                        previewImg = new DownloadedContent(null, provider.getPreviewImageUrl());
                    } else {
                        mp4 = saveContent("mp4", responseBody);
                        previewImg = createTempFile("jpg");
                        system("convert",
                               mp4.path + "[0]",
                               previewImg.path.toString());
                    }
                    String trackingId = UUID.randomUUID().toString();
                    log.info("Sending video message with trackingId={}", trackingId);
                    reply(event.getReplyToken(),
                          VideoMessage.builder()
                                      .originalContentUrl(mp4.getUri())
                                      .previewImageUrl(previewImg.uri)
                                      .trackingId(trackingId)
                                      .build());
                });
    }

    @EventMapping
    public void handleVideoPlayCompleteEvent(VideoPlayCompleteEvent event) throws IOException {
        log.info("Got video play complete: tracking id={}", event.getVideoPlayComplete().getTrackingId());
        this.replyText(event.getReplyToken(),
                       "You played " + event.getVideoPlayComplete().getTrackingId());
    }

    @EventMapping
    public void handleFileMessageEvent(MessageEvent<FileMessageContent> event) {
        this.reply(event.getReplyToken(),
                   new TextMessage(String.format("Received '%s'(%d bytes)",
                                                 event.getMessage().getFileName(),
                                                 event.getMessage().getFileSize())));
    }

    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {
        log.info("unfollowed this bot: {}", event);
    }

    @EventMapping
    public void handleUnknownEvent(UnknownEvent event) {
        log.info("Got an unknown event!!!!! : {}", event);
    }

    @EventMapping
    public void handleFollowEvent(FollowEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got followed event");
    }

    @EventMapping
    public void handleJoinEvent(JoinEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Joined " + event.getSource());
    }

    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken,
                       "Got postback data " + event.getPostbackContent().getData() + ", param " + event
                               .getPostbackContent().getParams().toString());
    }

    @EventMapping
    public void handleBeaconEvent(BeaconEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got beacon message " + event.getBeacon().getHwid());
    }

    @EventMapping
    public void handleMemberJoined(MemberJoinedEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got memberJoined message " + event.getJoined().getMembers()
                                                                      .stream().map(Source::getUserId)
                                                                      .collect(Collectors.joining(",")));
    }

    @EventMapping
    public void handleMemberLeft(MemberLeftEvent event) {
        log.info("Got memberLeft message: {}", event.getLeft().getMembers()
                                                    .stream().map(Source::getUserId)
                                                    .collect(Collectors.joining(",")));
    }

    @EventMapping
    public void handleMemberLeft(UnsendEvent event) {
        log.info("Got unsend event: {}", event);
    }

    @EventMapping
    public void handleOtherEvent(Event event) {
        log.info("Received message(Ignored): {}", event);
    }

    private void reply(@NonNull String replyToken, @NonNull Message message) {
        reply(replyToken, singletonList(message));
    }

    private void reply(@NonNull String replyToken, @NonNull List<Message> messages) {
        reply(replyToken, messages, false);
    }

    private void reply(@NonNull String replyToken,
                       @NonNull List<Message> messages,
                       boolean notificationDisabled) {
        try {
            BotApiResponse apiResponse = lineMessagingClient
                    .replyMessage(new ReplyMessage(replyToken, messages, notificationDisabled))
                    .get();
            log.info("Sent messages: {}", apiResponse);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void replyText(@NonNull String replyToken, @NonNull String message) {
        if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000 - 2) + "……";
        }
        this.reply(replyToken, new TextMessage(message));
    }

    private void handleHeavyContent(String replyToken, String messageId,
                                    Consumer<MessageContentResponse> messageConsumer) {
        final MessageContentResponse response;
        try {
            response = lineBlobClient.getMessageContent(messageId)
                                     .get();
        } catch (InterruptedException | ExecutionException e) {
            reply(replyToken, new TextMessage("Cannot get image: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        messageConsumer.accept(response);
    }

    private void handleSticker(String replyToken, StickerMessageContent content) {
        reply(replyToken, new StickerMessage(
                content.getPackageId(), content.getStickerId())
        );
    }

    private void handleTextContent(String replyToken, Event event, TextMessageContent content)
            throws Exception {
        final String text = content.getText();

        log.info("Got text message from replyToken:{}: text:{} emojis:{}", replyToken, text,
                 content.getEmojis());
        switch (text) {
            case "profile": {
                String replyText = "Hi, I am Eric Lin. I am majoring Information Management in National Yang Ming Chiao Tung University. My research is more like distributed system.\nThe latest project I joined is a chatbot. This competition was held by TSMC and Microsoft. We used Azure Services to build a food chatbot in two days.";
                log.info("Invoking 'profile' command: source:{}",
                         event.getSource());
                this.replyText(
                        replyToken,
                        replyText
                        // "我是林劭宇，目前就讀於國立陽明交通大學資訊管理研究所碩士班，研究室的方向是分散式系統。\n
                        // 最近做的專案是參賽台積電與微軟合辦的careerhack，透過兩天的時間利用Azure的服務開發出一個聊天機器人，
                        // 這個機器人主要是推薦美食，而且可以讓使用者可以將餐廳加到我的最愛，也會依照使用者的喜好做個人化的推薦，
                        // 其中我負責Azure環境的部署、版本控制、資料庫與CI/CD的部分。\n
                        // 我的個性很悶騷，一開始可能看起來會比較難親近，但彼此熟悉之後會發現其實我很外向，喜歡針對一件事的不同
                        // 面向思考，雖然有時讓我陷入死胡同、自我懷疑，但也因為這個特質，讓我有更多的機會可以看到別人沒看到的地方。"
                );
                break;
            }
            case "bye": {
                Source source = event.getSource();
                if (source instanceof GroupSource) {
                    this.replyText(replyToken, "Leaving group");
                    lineMessagingClient.leaveGroup(((GroupSource) source).getGroupId()).get();
                } else if (source instanceof RoomSource) {
                    this.replyText(replyToken, "Leaving room");
                    lineMessagingClient.leaveRoom(((RoomSource) source).getRoomId()).get();
                } else {
                    this.replyText(replyToken, "Bot can't leave from 1:1 chat");
                }
                break;
            }
            case "group_summary": {
                Source source = event.getSource();
                if (source instanceof GroupSource) {
                    GroupSummaryResponse groupSummary = lineMessagingClient.getGroupSummary(
                            ((GroupSource) source).getGroupId()).get();
                    this.replyText(replyToken, "Group summary: " + groupSummary);
                } else {
                    this.replyText(replyToken, "You can't use 'group_summary' command for "
                                               + source);
                }
                break;
            }
            case "group_member_count": {
                Source source = event.getSource();
                if (source instanceof GroupSource) {
                    GroupMemberCountResponse groupMemberCountResponse = lineMessagingClient.getGroupMemberCount(
                            ((GroupSource) source).getGroupId()).get();
                    this.replyText(replyToken, "Group member count: "
                                               + groupMemberCountResponse.getCount());
                } else {
                    this.replyText(replyToken, "You can't use 'group_member_count' command  for "
                                               + source);
                }
                break;
            }
            case "room_member_count": {
                Source source = event.getSource();
                if (source instanceof RoomSource) {
                    RoomMemberCountResponse roomMemberCountResponse = lineMessagingClient.getRoomMemberCount(
                            ((RoomSource) source).getRoomId()).get();
                    this.replyText(replyToken, "Room member count: "
                                               + roomMemberCountResponse.getCount());
                } else {
                    this.replyText(replyToken, "You can't use 'room_member_count' command  for "
                                               + source);
                }
                break;
            }
            case "confirm": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                        "Do it?",
                        new MessageAction("Yes", "Yes!"),
                        new MessageAction("No", "No!")
                );
                TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "buttons": {
                URI imageUrl = createUri("/static/buttons/1040.jpg");
                ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                        imageUrl,
                        "My button sample",
                        "Hello, my button",
                        Arrays.asList(
                                new URIAction("Go to line.me",
                                              URI.create("https://line.me"), null),
                                new PostbackAction("Say hello1",
                                                   "hello こんにちは"),
                                new PostbackAction("言 hello2",
                                                   "hello こんにちは",
                                                   "hello こんにちは"),
                                new MessageAction("Say message",
                                                  "Rice=米")
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Button alt text", buttonsTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "carousel": {
                URI imageUrl = createUri("/static/buttons/1040.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new URIAction("Go to line.me",
                                                      URI.create("https://line.me"), null),
                                        new URIAction("Go to line.me",
                                                      URI.create("https://line.me"), null),
                                        new PostbackAction("Say hello1",
                                                           "hello こんにちは")
                                )),
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new PostbackAction("言 hello2",
                                                           "hello こんにちは",
                                                           "hello こんにちは"),
                                        new PostbackAction("言 hello2",
                                                           "hello こんにちは",
                                                           "hello こんにちは"),
                                        new MessageAction("Say message",
                                                          "Rice=米")
                                )),
                                new CarouselColumn(imageUrl, "Datetime Picker",
                                                   "Please select a date, time or datetime", Arrays.asList(
                                        DatetimePickerAction.OfLocalDatetime
                                                .builder()
                                                .label("Datetime")
                                                .data("action=sel")
                                                .initial(LocalDateTime.parse("2017-06-18T06:15"))
                                                .min(LocalDateTime.parse("1900-01-01T00:00"))
                                                .max(LocalDateTime.parse("2100-12-31T23:59"))
                                                .build(),
                                        DatetimePickerAction.OfLocalDate
                                                .builder()
                                                .label("Date")
                                                .data("action=sel&only=date")
                                                .initial(LocalDate.parse("2017-06-18"))
                                                .min(LocalDate.parse("1900-01-01"))
                                                .max(LocalDate.parse("2100-12-31"))
                                                .build(),
                                        DatetimePickerAction.OfLocalTime
                                                .builder()
                                                .label("Time")
                                                .data("action=sel&only=time")
                                                .initial(LocalTime.parse("06:15"))
                                                .min(LocalTime.parse("00:00"))
                                                .max(LocalTime.parse("23:59"))
                                                .build()
                                ))
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "image_carousel": { //life photos
                URI imageUrl = createUri("/static/buttons/1040.jpg");
                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(imageUrl,
                                                        new URIAction("Goto line.me",
                                                                      URI.create("https://line.me"), null)
                                ),
                                new ImageCarouselColumn(imageUrl,
                                                        new MessageAction("Say message",
                                                                          "Rice=米")
                                ),
                                new ImageCarouselColumn(imageUrl,
                                                        new PostbackAction("言 hello2",
                                                                           "hello こんにちは",
                                                                           "hello こんにちは")
                                )
                        ));
                TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text",
                                                                      imageCarouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "imagemap": {
                //            final String baseUrl,
                //            final String altText,
                //            final ImagemapBaseSize imagemapBaseSize,
                //            final List<ImagemapAction> actions) {
                this.reply(replyToken, ImagemapMessage
                        .builder()
                        .baseUrl(createUri("/static/rich"))
                        .altText("This is alt text")
                        .baseSize(new ImagemapBaseSize(1040, 1040))
                        .actions(Arrays.asList(
                                URIImagemapAction.builder()
                                                 .linkUri("https://store.line.me/family/manga/en")
                                                 .area(new ImagemapArea(0, 0, 520, 520))
                                                 .build(),
                                URIImagemapAction.builder()
                                                 .linkUri("https://store.line.me/family/music/en")
                                                 .area(new ImagemapArea(520, 0, 520, 520))
                                                 .build(),
                                URIImagemapAction.builder()
                                                 .linkUri("https://store.line.me/family/play/en")
                                                 .area(new ImagemapArea(0, 520, 520, 520))
                                                 .build(),
                                MessageImagemapAction.builder()
                                                     .text("URANAI!")
                                                     .area(new ImagemapArea(520, 520, 520, 520))
                                                     .build()
                        ))
                        .build());
                break;
            }
            case "imagemap_video": {
                this.reply(replyToken, ImagemapMessage
                        .builder()
                        .baseUrl(createUri("/static/imagemap_video"))
                        .altText("This is an imagemap with video")
                        .baseSize(new ImagemapBaseSize(722, 1040))
                        .video(
                                ImagemapVideo.builder()
                                             .originalContentUrl(
                                                     createUri("/static/imagemap_video/originalContent.mp4"))
                                             .previewImageUrl(
                                                     createUri("/static/imagemap_video/previewImage.jpg"))
                                             .area(new ImagemapArea(40, 46, 952, 536))
                                             .externalLink(
                                                     new ImagemapExternalLink(
                                                             URI.create("https://example.com/see_more.html"),
                                                             "See More")
                                             )
                                             .build()
                        )
                        .actions(singletonList(
                                MessageImagemapAction.builder()
                                                     .text("NIXIE CLOCK")
                                                     .area(new ImagemapArea(260, 600, 450, 86))
                                                     .build()
                        ))
                        .build());
                break;
            }
            case "github": {
                URI imageUrl = createUri("/static/buttons/9919.jpg");
                ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                        imageUrl,
                        "My github site",
                        "   ",
                        Arrays.asList(
                                new URIAction("Go to Eric's github",
                                              URI.create("https://github.com/ericlin03"), null)
                        ));
                TemplateMessage templateMessage = new TemplateMessage("My github: https://github.com/ericlin03", buttonsTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "experience": {
                // Application Security Intern
                URI CTBC = createUri("/static/buttons/CTBC.jpg");
                URI microsoft = createUri("/static/buttons/microsoft.jpg");
                URI fju = createUri("/static/buttons/FJU.jpg");
                // String CTBC_text = "Application Security Intern\n● Responsible for the black- and white-box testing of over 20 systems\n● Built environment of white-box testing, imported policy package\n● Updated policy package of black-box testing, recorded scripts of black-box testing\n● Pre-reviewed vulnerability of systems before online";
                // String microsoft_text = "2021 Microsoft & TSMC Careerhack\n● Be shortlisted for the final contest and built a online chatbot with Azure\n● Responsible for version control, Database, and application deployment";
                // String fju_text = "Blockchain Ticketing Platform and Payment Project\n● Built private blockchain with Ethereum\n● Wrote smart contract and deployed on blockchain with Solidity\n● Wrote API for website and blockchain with JavaScript";
                
                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(CTBC,
                                                        new MessageAction("CTBC intern",
                                                                          "App Security Intern\n● Responsible for the black- and white-box testing of over 20 systems\n● Built environment of white-box testing, imported policy package\n● Updated policy package of black-box testing, recorded scripts of black-box testing\n● Pre-reviewed vulnerability of systems before online")
                                                        // new PostbackAction("CTBC intern",
                                                        //                    "Security Intern",
                                                        //                    "Application Security Intern\n● Responsible for the black- and white-box testing of over 20 systems\n● Built environment of white-box testing, imported policy package\n● Updated policy package of black-box testing, recorded scripts of black-box testing\n● Pre-reviewed vulnerability of systems before online")
                                ),
                                new ImageCarouselColumn(microsoft,
                                                        new MessageAction("Careerhack",
                                                                          "2021 Microsoft & TSMC Careerhack\n● Be shortlisted for the final contest and built a online chatbot with Azure\n● Responsible for version control, Database, and application deployment")
                                                        // new PostbackAction("Careerhack",
                                                        //                    "Careerhack",
                                                        //                    "2021 Microsoft & TSMC Careerhack\n● Be shortlisted for the final contest and built a online chatbot with Azure\n● Responsible for version control, Database, and application deployment")
                                ),
                                new ImageCarouselColumn(fju,
                                                        new MessageAction("FJU project",
                                                                          "Blockchain Ticketing Platform and Payment Project\n● Built private blockchain with Ethereum\n● Wrote smart contract and deployed on blockchain with Solidity\n● Wrote API for website and blockchain with JavaScript")
                                                        // new PostbackAction("FJU final project",
                                                        //                    "Ticketing Platform",
                                                        //                    "Blockchain Ticketing Platform and Payment Project\n● Built private blockchain with Ethereum\n● Wrote smart contract and deployed on blockchain with Solidity\n● Wrote API for website and blockchain with JavaScript")
                                )
                        ));
                TemplateMessage templateMessage = new TemplateMessage("My work and project experience",
                                                                      imageCarouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            // case "flex": {
            //     this.reply(replyToken, new ExampleFlexMessageSupplier().get());
            //     break;
            // }
            // case "quickreply": {
            //     this.reply(replyToken, new MessageWithQuickReplySupplier().get());
            //     break;
            // }
            // case "no_notify": {
            //     this.reply(replyToken,
            //                singletonList(new TextMessage("This message is send without a push notification")),
            //                true);
            //     break;
            // }
            default:
                String replyText = "Hi, this bot is Eric introduction chatbot. You can input below texts or click rich menu to know more about me.\nprofile: my introduction📜\ngithub: my github site💻\nexperience: my work experience💼\nskills: what I can do🛠\ninterest: what I like to do🏀";
                // log.info("Returns echo message {}: {}", replyToken, text);
                this.replyText(
                        replyToken,
                        replyText
                        // "嗨，這是林劭宇的自我介紹機器人，可以輸入以下文字或是點選圖文選單來更加了解我哦!\n
                        // profile: 簡介\n
                        // github: 我的github site\n
                        // experience: 我的工作經驗\n
                        // skills: 我會的技能\n
                        // interests: 平常的興趣"
                );
                break;
        }
    }

    private static URI createUri(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                                          .scheme("https")
                                          .path(path).build()
                                          .toUri();
    }

    private void system(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        try {
            Process start = processBuilder.start();
            int i = start.waitFor();
            log.info("result: {} =>  {}", Arrays.toString(args), i);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            log.info("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static DownloadedContent saveContent(String ext, MessageContentResponse responseBody) {
        log.info("Got content-type: {}", responseBody);

        DownloadedContent tempFile = createTempFile(ext);
        try (OutputStream outputStream = Files.newOutputStream(tempFile.path)) {
            ByteStreams.copy(responseBody.getStream(), outputStream);
            log.info("Saved {}: {}", ext, tempFile);
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DownloadedContent createTempFile(String ext) {
        String fileName = LocalDateTime.now().toString() + '-' + UUID.randomUUID() + '.' + ext;
        Path tempFile = KitchenSinkApplication.downloadedContentDir.resolve(fileName);
        tempFile.toFile().deleteOnExit();
        return new DownloadedContent(
                tempFile,
                createUri("/downloaded/" + tempFile.getFileName()));
    }

    @Value
    private static class DownloadedContent {
        Path path;
        URI uri;
    }
}
