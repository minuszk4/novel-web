export interface DemoChapter {
  id: string;
  title: string;
  publishedLabel: string;
  paragraphs: string[];
}

export const demoWork = {
  id: 'demo-story',
  title: 'Bưu Điện Cuối Con Đường Mưa',
  author: 'Tác phẩm mẫu do Novel tạo',
  description:
    'Mỗi tối thứ Năm, một bức thư không đề tên người gửi lại xuất hiện trong hòm thư cũ ở cuối phố. Linh bắt đầu tìm người nhận, rồi nhận ra những lá thư đang dẫn cô về một ký ức tưởng đã quên.',
  tags: ['Kỳ ảo đời thường', 'Bí ẩn', 'Tác phẩm mẫu'],
};

export const demoChapters: DemoChapter[] = [
  {
    id: 'chapter-1',
    title: 'Bức thư không có người gửi',
    publishedLabel: 'Chương 1',
    paragraphs: [
      'Cơn mưa đầu mùa trút xuống con phố nhỏ vào lúc tiệm đồng hồ của ông Tư vừa tắt đèn. Linh kéo cao cổ áo, chạy qua mái hiên cuối cùng rồi dừng trước chiếc hòm thư xanh đã bạc màu. Trên nắp hòm có một giọt nước đọng lại, tròn như hạt thủy tinh.',
      'Bên trong là một phong thư màu ngà. Không tem, không địa chỉ người gửi. Chỉ có tên cô được viết bằng mực xanh: “Gửi Linh, người vẫn nhớ con đường này”. Nét chữ nghiêng sang trái, giống hệt những dòng chữ trong cuốn sổ cũ của mẹ.',
      'Cô nhìn quanh. Con phố vắng tanh, chỉ có tiếng mưa gõ lên mái tôn và tiếng xe buýt xa dần ở ngã tư. Linh đã sống ở đây từ nhỏ, nhưng chưa từng thấy ai dùng chiếc hòm thư này. Người ta bảo bưu điện cuối phố đóng cửa từ trước khi cô vào lớp Một.',
      'Phong thư không có dấu niêm. Bên trong là một mảnh giấy gấp làm tư, thơm mùi gỗ tuyết tùng. “Nếu con còn giữ chiếc chìa khóa đồng, hãy đến căn phòng có cửa sổ nhìn ra phía đông.” Chỉ vậy. Không ký tên.',
      'Linh siết chiếc chìa khóa đang nằm trong túi áo. Mẹ đã đưa nó cho cô vào một buổi chiều tháng Chín, dặn rằng có những cánh cửa chỉ mở khi người ta thôi sợ câu trả lời. Khi ấy, Linh nghĩ đó chỉ là một câu nói đùa.',
      'Mưa nhỏ dần. Cô gấp lá thư, cất vào ví rồi ngẩng nhìn con đường dẫn về căn nhà cũ của mẹ. Lần đầu tiên sau nhiều năm, Linh không đi đường tắt.',
    ],
  },
  {
    id: 'chapter-2',
    title: 'Căn phòng hướng đông',
    publishedLabel: 'Chương 2',
    paragraphs: [
      'Căn nhà của mẹ vẫn đứng sau hàng phượng già, im lặng như thể đã quen với việc chờ đợi. Linh mở cánh cổng kêu cót két. Chìa khóa đồng lạnh trong lòng bàn tay, nặng hơn cô nhớ.',
      'Phòng hướng đông nằm trên gác mái. Cô phải đẩy chiếc tủ gỗ mới thấy ổ khóa nhỏ sau lớp giấy dán tường. Chìa khóa vừa khít. Cánh cửa mở ra, mang theo mùi giấy cũ và nắng đã ngủ quên.',
      'Trong phòng không có đồ đạc, chỉ có một chiếc bàn đặt sát cửa sổ. Trên bàn là chiếc máy đánh chữ màu đen, một cuộn ruy-băng đã phai và tấm ảnh chụp mẹ đứng trước hòm thư xanh. Phía sau ảnh có dòng chữ: “Nơi này giữ những điều ta chưa kịp nói.”',
      'Linh kéo ngăn bàn. Một chồng thư được buộc bằng sợi chỉ đỏ, mỗi lá đều ghi tên một người trong phố. Lá trên cùng mang tên ông Tư, người thợ đồng hồ vừa đóng tiệm lúc nãy.',
      'Cô nghe tiếng chuông xe đạp ngoài sân. Một người dừng trước cổng rồi đặt thứ gì đó vào khe cửa. Khi Linh chạy xuống, con phố đã trống. Trên nền gạch chỉ còn một phong thư mới, nét chữ xanh nghiêng sang trái.',
    ],
  },
  {
    id: 'chapter-3',
    title: 'Người giữ những điều chưa nói',
    publishedLabel: 'Chương 3',
    paragraphs: [
      'Ông Tư nhận lá thư bằng hai bàn tay run nhẹ. Ông không hỏi Linh tìm thấy nó ở đâu. Ông chỉ đứng thật lâu dưới mái hiên, nhìn dòng chữ trên phong bì như thể nghe thấy tiếng ai gọi từ một nơi rất xa.',
      '“Có những lời mình để dành quá lâu,” ông nói. “Đến khi muốn nói thì người nghe đã đi mất.” Linh không biết nên trả lời thế nào. Cô chỉ kể cho ông về căn phòng trên gác và chiếc máy đánh chữ của mẹ.',
      'Tối ấy, họ cùng quay lại căn phòng hướng đông. Ông Tư nhận ra nét chữ trên các phong thư là của người chị gái đã rời phố hơn hai mươi năm trước. Bà từng làm việc ở bưu điện cuối đường, nơi những lá thư không bao giờ được gửi đi được cất giữ.',
      'Linh mở lá thư dành cho ông. Bên trong không có lời xin lỗi hay lời hứa hẹn. Chỉ có một địa chỉ ở thành phố bên kia sông và câu nhắn: “Nếu còn muốn gặp nhau, lần này đừng đợi đến mùa mưa.”',
      'Ngày hôm sau, chiếc hòm thư xanh được sơn lại. Ông Tư đi chuyến xe sớm nhất. Linh ở lại, đặt chiếc chìa khóa đồng lên bàn rồi viết lá thư đầu tiên của mình. Ngoài cửa sổ, nắng vừa chạm tới con đường cũ.',
    ],
  },
];
