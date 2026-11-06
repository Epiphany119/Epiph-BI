import { genChartByAiUsingPOST } from '@/services/yubi/chartController';
import {
  BarChartOutlined,
  CheckCircleFilled,
  CloudUploadOutlined,
  FileExcelOutlined,
  LoadingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  message,
  Row,
  Select,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import TextArea from 'antd/es/input/TextArea';
import React, { useState } from 'react';
import ReactECharts from 'echarts-for-react';
import './index.less';

const { Text, Title } = Typography;

/** 从已选文件列表取出浏览器 File；同页再次提交会重新发送同一个 File。 */
const getSelectedFile = (files: UploadFile[]): File | undefined => {
  const uploadFile = files[0];
  if (uploadFile?.originFileObj) return uploadFile.originFileObj as File;
  return typeof File !== 'undefined' && uploadFile instanceof File ? uploadFile : undefined;
};

/** 尽量展示服务端提供的业务错误，方便用户直接修正分析目标或文件。 */
const getErrorText = (error: any) =>
  error?.response?.data?.message || error?.data?.message || error?.message || '请稍后重试';

/** 智能分析页：文件只在用户替换或重置时清除，修改目标可直接复用。 */
const AddChart: React.FC = () => {
  const [form] = Form.useForm();
  const [chart, setChart] = useState<API.BiResponse>();
  const [option, setOption] = useState<any>();
  const [submitting, setSubmitting] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [failure, setFailure] = useState('');

  const onFinish = async (values: any) => {
    if (submitting) return;
    const selectedFile = getSelectedFile(fileList);
    if (!selectedFile) {
      message.error('请先选择表格文件');
      return;
    }

    setSubmitting(true);
    setFailure('');
    setChart(undefined);
    setOption(undefined);
    const params = { ...values, file: undefined };

    try {
      const res = await genChartByAiUsingPOST(params, {}, selectedFile);
      if (!res?.data) {
        const reason = res?.message || '服务器没有返回分析结果';
        setFailure(reason);
        message.error(reason);
        return;
      }
      const chartOption = JSON.parse(res.data.genChart ?? 'null');
      if (!chartOption) throw new Error('图表配置为空');
      setChart(res.data);
      setOption(chartOption);
      message.success('分析完成');
    } catch (error: any) {
      const reason = getErrorText(error);
      setFailure(reason);
      message.error(`分析失败：${reason}`);
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
    form.resetFields();
    setFileList([]);
    setChart(undefined);
    setOption(undefined);
    setFailure('');
  };

  return (
    <div className="add-chart-page">
      <section className="analysis-hero">
        <div className="analysis-hero__copy">
          <Tag className="analysis-hero__eyebrow" icon={<ThunderboltOutlined />}>
            EPIPH BI · 智能数据分析
          </Tag>
          <Title level={2}>让表格里的信息，变成清晰结论</Title>
          <Text className="analysis-hero__description">
            上传一份表格，描述你关心的问题。系统会先在本地统计真实数据，再生成图表和分析说明。
          </Text>
        </div>
        <div className="analysis-hero__mark" aria-hidden="true">
          <BarChartOutlined />
        </div>
      </section>

      <Row gutter={[20, 20]} className="analysis-workspace">
        <Col xs={24} xl={10}>
          <Card className="analysis-panel analysis-form-panel" bordered={false}>
            <div className="panel-heading">
              <div className="panel-heading__icon">
                <ThunderboltOutlined />
              </div>
              <div>
                <Title level={4}>创建分析</Title>
                <Text type="secondary">告诉我们要从这份数据里看什么</Text>
              </div>
            </div>

            <Form
              form={form}
              name="addChart"
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ chartType: '柱状图' }}
              className="analysis-form"
            >
              <Form.Item
                name="goal"
                label="分析目标"
                rules={[{ required: true, whitespace: true, message: '请描述你要分析的问题' }]}
              >
                <TextArea
                  rows={4}
                  maxLength={200}
                  showCount
                  placeholder="例如：按商圈统计门店数量，比较各区域的分布"
                />
              </Form.Item>

              <Row gutter={12}>
                <Col span={14}>
                  <Form.Item name="name" label="图表名称">
                    <Input maxLength={40} placeholder="例如：门店区域分布" />
                  </Form.Item>
                </Col>
                <Col span={10}>
                  <Form.Item name="chartType" label="图表类型">
                    <Select
                      options={[
                        { value: '柱状图', label: '柱状图' },
                        { value: '折线图', label: '折线图' },
                        { value: '饼图', label: '饼图' },
                        { value: '堆叠图', label: '堆叠图' },
                        { value: '雷达图', label: '雷达图' },
                      ]}
                    />
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item
                name="file"
                label="分析数据"
                valuePropName="fileList"
                getValueFromEvent={(event) => event?.fileList ?? []}
                rules={[
                  {
                    validator: () =>
                      fileList.length
                        ? Promise.resolve()
                        : Promise.reject(new Error('请先选择 XLSX 或 CSV 文件')),
                  },
                ]}
              >
                <Upload
                  name="file"
                  accept=".xlsx,.csv"
                  maxCount={1}
                  fileList={fileList}
                  beforeUpload={() => false}
                  onChange={({ fileList: nextFileList }) => {
                    setFileList(nextFileList as UploadFile[]);
                    form.setFieldValue('file', nextFileList);
                  }}
                  onRemove={() => {
                    setFileList([]);
                    form.setFieldValue('file', []);
                  }}
                >
                  <div className="upload-dropzone">
                    <CloudUploadOutlined className="upload-dropzone__icon" />
                    <div className="upload-dropzone__title">点击选择文件</div>
                    <div className="upload-dropzone__hint">支持 XLSX / CSV，最大 5MB</div>
                  </div>
                </Upload>
              </Form.Item>
              <div className="reuse-note">
                <FileExcelOutlined />
                <span>修改分析目标后可再次提交，已选文件会继续保留。</span>
              </div>

              <div className="analysis-form__actions">
                <Button
                  type="primary"
                  htmlType="submit"
                  size="large"
                  icon={submitting ? <LoadingOutlined /> : <ThunderboltOutlined />}
                  loading={submitting}
                >
                  {submitting ? '正在分析' : '开始分析'}
                </Button>
                <Button size="large" onClick={reset} disabled={submitting}>
                  重置
                </Button>
              </div>
            </Form>
          </Card>
        </Col>

        <Col xs={24} xl={14}>
          <Card className="analysis-panel analysis-result-panel" bordered={false}>
            <div className="panel-heading panel-heading--result">
              <div className="panel-heading__icon panel-heading__icon--green">
                {chart ? <CheckCircleFilled /> : <BarChartOutlined />}
              </div>
              <div>
                <Title level={4}>分析结果</Title>
                <Text type="secondary">
                  {chart ? '根据当前表格生成的图表和结论' : '提交分析后，结果会显示在这里'}
                </Text>
              </div>
              {chart && <Tag color="success">已完成</Tag>}
            </div>

            <Spin spinning={submitting} tip="正在统计数据并生成分析结果…">
              {chart && option ? (
                <div className="analysis-result">
                  <div className="result-summary">
                    <Text className="result-summary__label">分析结论</Text>
                    <div className="result-summary__text">{chart.genResult}</div>
                  </div>
                  <div className="result-chart">
                    <ReactECharts option={option} notMerge style={{ height: 390, width: '100%' }} />
                  </div>
                </div>
              ) : failure ? (
                <div className="analysis-error">
                  <div className="analysis-error__symbol">!</div>
                  <Title level={5}>这次分析没有完成</Title>
                  <Text>{failure}</Text>
                  <div className="analysis-error__tip">
                    修改分析目标后可以直接重试，当前选择的文件仍会保留。
                  </div>
                </div>
              ) : (
                <Empty
                  className="analysis-empty"
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    <span>
                      {fileList.length
                        ? '填写分析目标，开始查看数据洞察'
                        : '上传表格后，分析结果会显示在这里'}
                    </span>
                  }
                />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default AddChart;
