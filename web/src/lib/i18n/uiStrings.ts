import type { UiStrings, UiText } from '@flare/web-presenters/homeTimelineWithTabs.svelte';
import { m } from '$lib/paraglide/messages.js';

export type { UiStrings };

export function localizedUiText(value: UiText): string {
	switch (value.type) {
		case 'ExternalRef':
			return value.fallback;
		case 'Localized':
			return localizedUiString(value.string);
		case 'Raw':
			return value.string;
	}
}

export function localizedUiString(value: UiStrings): string {
	switch (value) {
		case 'Home':
			return m.homeTabHomeTitle();
		case 'Notifications':
			return m.homeTabNotificationsTitle();
		case 'Discover':
			return m.homeTabDiscoverTitle();
		case 'Me':
			return m.homeTabMeTitle();
		case 'Settings':
			return m.settingsTitle();
		case 'MastodonLocal':
			return m.mastodonTabLocalTitle();
		case 'MastodonPublic':
			return m.mastodonTabPublicTitle();
		case 'Featured':
			return m.homeTabFeaturedTitle();
		case 'Bookmark':
			return m.homeTabBookmarksTitle();
		case 'Favourite':
			return m.homeTabFavoriteTitle();
		case 'List':
			return m.homeTabListTitle();
		case 'Feeds':
			return m.homeTabFeedsTitle();
		case 'DirectMessage':
			return m.dmListTitle();
		case 'Rss':
			return m.rssTitle();
		case 'Antenna':
			return m.antennaTitle();
		case 'MixedTimeline':
			return m.homeTabMixedTimelineTitle();
		case 'Social':
			return m.homeTabSocialTimelineTitle();
		case 'Liked':
			return m.likedTitle();
		case 'AllRssFeeds':
			return m.allRssFeedsTitle();
		case 'Posts':
			return m.postsTitle();
		case 'PostsWithReplies':
			return m.profileTabTimelineWithReply();
		case 'Highlights':
			return m.profileTabHighlights();
		case 'Reposts':
			return m.tabSettingsFilterRepost();
		case 'Articles':
			return m.profileTabArticles();
		case 'WeiboFriendsCircle':
			return m.weiboFriendsCircleTitle();
		case 'WeiboPrivateFollowing':
			return m.weiboPrivateFollowingTitle();
		case 'Media':
			return m.profileTabMedia();
		case 'Channel':
			return m.homeTabChannelsTitle();
		case 'Default':
			return m.tabSettingsDefault();
		case 'Login':
			return m.loginButton();
		case 'Verify':
			return m.loginVerifying();
		case 'Cancel':
			return m.loginCancel();
		case 'Next':
			return m.loginNext();
		case 'Username':
			return m.loginUsername();
		case 'Password':
			return m.loginPassword();
		case 'Otp':
			return m.loginOtp();
		case 'OAuthLogin':
			return m.loginOauth();
		case 'PasswordLogin':
			return m.loginPassword();
		case 'QrConnect':
			return m.loginQrConnect();
		case 'CredentialImport':
			return m.loginCredentialImport();
		case 'ExternalSigner':
			return m.loginExternalSigner();
		case 'WebCookieLogin':
			return m.loginWebCookie();
		case 'PixivRankingWeek':
			return m.pixivRankingWeekTitle();
		case 'PixivRankingMonth':
			return m.pixivRankingMonthTitle();
		case 'PixivRankingDayMale':
			return m.pixivRankingDayMaleTitle();
		case 'PixivRankingDayFemale':
			return m.pixivRankingDayFemaleTitle();
		case 'PixivRankingWeekOriginal':
			return m.pixivRankingWeekOriginalTitle();
		case 'PixivRankingWeekRookie':
			return m.pixivRankingWeekRookieTitle();
		case 'PixivRankingDayManga':
			return m.pixivRankingDayMangaTitle();
		case 'PixivRankingDayAi':
			return m.pixivRankingDayAiTitle();
		case 'PixivRankingDayR18':
			return m.pixivRankingDayR18Title();
		case 'PixivRankingWeekR18':
			return m.pixivRankingWeekR18Title();
		case 'PixivRankingDayR18Ai':
			return m.pixivRankingDayR18AiTitle();
		case 'Illustrations':
			return m.illustrationsTitle();
		case 'Manga':
			return m.mangaTitle();
		case 'FanboxSupported':
			return m.fanboxSupportedTitle();
		case 'FanboxRecommendedCreators':
			return m.fanboxRecommendedCreatorsTitle();
		case 'PixivPrivateFollowing':
			return m.pixivPrivateFollowingTitle();
		case 'PixivPrivateBookmarks':
		case 'PixivPrivateFavourites':
			return m.pixivPrivateFavoritesTitle();
		default:
			return m.loginCredentialImport();
	}
}
