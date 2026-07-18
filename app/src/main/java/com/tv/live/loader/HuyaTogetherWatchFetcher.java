List<Channel> allList = fetcher.fetchAllTogetherWatch(15);

// 电影细分集合
List<Channel> movieXiJu = new ArrayList<>();    // 一起看电影 (喜剧)
List<Channel> movieDongZuo = new ArrayList<>(); // 一起看电影 (动作)
List<Channel> movieJingSong = new ArrayList<>(); // 一起看电影 (惊悚)
List<Channel> movieKeHuan = new ArrayList<>();  // 一起看电影 (科幻)
List<Channel> movieGuZhuang = new ArrayList<>();// 一起看电影 (古装)

// 电视剧细分集合
List<Channel> tvGuZhuang = new ArrayList<>();    // 一起看电视剧 (古装)
List<Channel> tvJunLv = new ArrayList<>();      // 一起看电视剧 (军旅)
List<Channel> tvGaoXiao = new ArrayList<>();    // 一起看电视剧 (搞笑)
List<Channel> tvXuanYi = new ArrayList<>();      // 一起看电视剧 (悬疑)
List<Channel> tvDuShi = new ArrayList<>();      // 一起看电视剧 (都市)

// 通用大类
List<Channel> animeList = new ArrayList<>();    // 一起看动画
List<Channel> varietyList = new ArrayList<>();   // 一起看综艺
List<Channel> otherList = new ArrayList<>();     // 未知分类

// 循环遍历全部频道，按细分group分流
for (Channel c : allList) {
    String group = c.getGroup();
    switch (group) {
        case "一起看电影 (喜剧)":
            movieXiJu.add(c);
            break;
        case "一起看电影 (动作)":
            movieDongZuo.add(c);
            break;
        case "一起看电影 (惊悚)":
            movieJingSong.add(c);
            break;
        case "一起看电影 (科幻)":
            movieKeHuan.add(c);
            break;
        case "一起看电影 (古装)":
            movieGuZhuang.add(c);
            break;
        case "一起看电视剧 (古装)":
            tvGuZhuang.add(c);
            break;
        case "一起看电视剧 (军旅)":
            tvJunLv.add(c);
            break;
        case "一起看电视剧 (搞笑)":
            tvGaoXiao.add(c);
            break;
        case "一起看电视剧 (悬疑)":
            tvXuanYi.add(c);
            break;
        case "一起看电视剧 (都市)":
            tvDuShi.add(c);
            break;
        case "一起看动画":
            animeList.add(c);
            break;
        case "一起看综艺":
            varietyList.add(c);
            default:
                otherList.add(c);
                break;
    }
}

// 每个分类对应适配器赋值
movieXiJuAdapter.setData(movieXiJu);
movieDongZuoAdapter.setData(movieDongZuo);
movieJingSongAdapter.setData(movieJingSong);
movieKeHuanAdapter.setData(movieKeHuan);
movieGuZhuangAdapter.setData(movieGuZhuang);

tvGuZhuangAdapter.setData(tvGuZhuang);
tvJunLvAdapter.setData(tvJunLv);
tvGaoXiaoAdapter.setData(tvGaoXiao);
tvXuanYiAdapter.setData(tvXuanYi);
tvDuShiAdapter.setData(tvDuShi);

animeAdapter.setData(animeList);
varietyAdapter.setData(varietyList);
otherAdapter.setData(otherList);
