import { genChartByAiAsyncUsingPOST } from '@/services/yubi/chartController';
import {
  ArrowRightOutlined,
  BarChartOutlined,
  CheckCircleFilled,
  CloudUploadOutlined,
  FileExcelOutlined,
  LoadingOutlined,
  RocketOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  message,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import TextArea from 'antd/es/input/TextArea';
import React, { useState } from 'react';
import './index.less';

const { Text, Title } = Typography;

/** 从页面保留的文件列表读取 File，支持修改分析目标后重复提交。 */
const getSelectedFile = (files: UploadFile[]): File | undefined => {
  const uploadFile = files[0];
  if (uploadFile?.originFileObj) return uploadFile.originFileObj as File;
  return typeof File !== 'undefined' && uploadFile instanceof File ? uploadFile : undefined;
};

/** 将后端错误优先展示给用户，方便修正请求后再次提交。 */
const getErrorText = (error: any) =>
  error?.response?.data?.message || error?.data?.message || error?.message || '请稍后重试';

/** 异步分析页：提交后任务在后台运行，文件在用户重置前一直保留。 */
const AddChartAsync: React.FC = () => {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [submitted, setSubmitted] = useState(false);
  const [submittedChartId, setSubmittedChartId] = useState<number>();
  const [failure, setFailure] = useState('');

  const onFinish = async (values: any) => {
    if (submitting) return;
    const selectedFile = getSelectedFile(fileList);
    if (!selectedFile) {
      message.error('请先选择 XLSX 或 CSV 文件');
      return;
    }

    setSubmitting(true);
    setFailure('');
    setSubmitted(false);
    setSubmittedChartId(undefined);
    // 文件由 multipart 参数单独提交，避免被重复放进普通参数。
    const params = { ...values, file: undefined };

    try {
      const res = await genChartByAiAsyncUsingPOST(params, {}, selectedFile);
      if (!res?.data) {
        const reason = res?.message || '服务器没有返回分析任务';
        setFailure(reason);
        message.error(reason);
        return;
      }
      setSubmitted(true);
      setSubmittedChartId(res.data.chartId);
      message.success('分析任务已提交');
    } catch (error: any) {
      const reason = getErrorText(error);
      setFailure(reason);
      message.error(`提交失败：${reason}`);
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
    form.resetFields();
    setFileList([]);
    setSubmitted(false);
    setSubmittedChartId(undefined);
    setFailure('');
  };

  return (
    <div className="add-chart-async-page">
      <section className="async-hero">
        <div className="async-hero__copy">
          <Tag className="async-hero__eyebrow" icon={<ThunderboltOutlined />}>
            EPIPH BI · 后台任务
          </Tag>
          <Title level={2}>让耗时分析，在后台稳步完成</Title>
          <Text className="async-hero__description">
            提交表格和分析目标后，任务会在后台运行。你可以继续操作，完成后到“我的图表”查看图表与结论。
          </Text>
        </div>
        <div className="async-hero__mark" aria-hidden="true">
          <RocketOutlined />
        </div>
      </section>

      <Row gutter={[20, 20]} className="async-workspace">
        <Col xs={24} xl={14}>
          <Card className="async-panel" bordered={false}>
            <div className="async-panel-heading">
              <div className="async-panel-heading__icon">
                <CloudUploadOutlined />
              </div>
              <div>
                <Title level={4}>创建异步分析</Title>
                <Text type="secondary">描述目标并选择一份表格，提交后由后台处理</Text>
              </div>
            </div>

            <Form
              form={form}
              name="addChartAsync"
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ chartType: '柱状图' }}
              className="async-form"
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
                  placeholder="例如：按商圈统计门店数量，并找出门店最多的商圈"
                />
              </Form.Item>

              <Row gutter={12}>
                <Col xs={24} sm={14}>
                  <Form.Item name="name" label="分析名称">
                    <Input maxLength={40} placeholder="例如：门店商圈分布" />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={10}>
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
                  beforeUpload={(file) => {
                    if (file.size > 5 * 1024 * 1024) {
                      message.error('文件不能超过 5MB');
                      return Upload.LIST_IGNORE;
                    }
                    return false;
                  }}
                  onChange={({ fileList: nextFileList }) => {
                    setFileList(nextFileList as UploadFile[]);
                    form.setFieldValue('file', nextFileList);
                    setFailure('');
                  }}
                  onRemove={() => {
                    setFileList([]);
                    form.setFieldValue('file', []);
                  }}
                >
                  <div className="async-upload-dropzone">
                    <CloudUploadOutlined className="async-upload-dropzone__icon" />
                    <div className="async-upload-dropzone__title">点击选择文件</div>
                    <div className="async-upload-dropzone__hint">支持 XLSX / CSV，最大 5MB</div>
                  </div>
                </Upload>
              </Form.Item>
              <div className="async-reuse-note">
                <FileExcelOutlined />
                <span>修改分析目标后可再次提交，当前选中的文件会继续保留。</span>
              </div>

              <div className="async-form__actions">
                <Button
                  type="primary"
                  htmlType="submit"
                  size="large"
                  icon={submitting ? <LoadingOutlined /> : <RocketOutlined />}
                  loading={submitting}
                >
                  {submitting ? '正在提交' : '提交后台分析'}
                </Button>
                <Button size="large" onClick={reset} disabled={submitting}>
                  重置
                </Button>
              </div>
            </Form>
          </Card>
        </Col>

        <Col xs={24} xl={10}>
          <Card className="async-panel async-status-panel" bordered={false}>
            <div className="async-panel-heading">
              <div className={`async-panel-heading__icon ${submitted ? 'is-success' : ''}`}>
                {submitted ? <CheckCircleFilled /> : <BarChartOutlined />}
              </div>
              <div>
                <Title level={4}>任务状态</Title>
                <Text type="secondary">提交后可在图表列表继续跟踪</Text>
              </div>
              {submitted && <Tag color="success">已提交</Tag>}
            </div>

            {failure ? (
              <div className="async-status-message async-status-message--error">
                <Text strong>任务没有提交</Text>
                <Text>{failure}</Text>
                <Text type="secondary">已选文件仍保留。调整目标或稍后重试即可。</Text>
              </div>
            ) : submitted ? (
              <div className="async-status-message async-status-message--success">
                <CheckCircleFilled className="async-status-message__check" />
                <Title level={5}>分析任务已进入后台</Title>
                {submittedChartId && <Text type="secondary">任务编号：{submittedChartId}</Text>}
                <Text>后台完成后，可在“我的图表”打开完整分析结果。</Text>
                <Button
                  type="primary"
                  icon={<ArrowRightOutlined />}
                  onClick={() => history.push('/my_chart')}
                >
                  查看我的图表
                </Button>
              </div>
            ) : (
              <div className="async-status-message">
                <div className="async-status-illustration">
                  <RocketOutlined />
                </div>
                <Title level={5}>分析完成后，结果不会丢</Title>
                <Text type="secondary">
                  提交成功后任务会保存到图表记录中。你可以离开此页面，之后再回来查看。
                </Text>
                <Button type="link" onClick={() => history.push('/my_chart')}>
                  前往我的图表 <ArrowRightOutlined />
                </Button>
              </div>
            )}

            <div className="async-steps">
              <div className="async-steps__title">异步分析流程</div>
              <div className="async-step">
                <span>01</span>
                <div>
                  <strong>提交数据</strong>
                  <small>选择表格并描述分析目标</small>
                </div>
              </div>
              <div className="async-step">
                <span>02</span>
                <div>
                  <strong>后台处理</strong>
                  <small>任务状态会自动更新</small>
                </div>
              </div>
              <div className="async-step">
                <span>03</span>
                <div>
                  <strong>查看结果</strong>
                  <small>在图表列表查看图表和结论</small>
                </div>
              </div>
            </div>

            <Space className="async-bottom-link">
              <Button type="link" onClick={() => history.push('/dashboard')}>
                返回分析工作台
              </Button>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default AddChartAsync;
