import { genChartByAiAsyncUsingPOST } from '@/services/yubi/chartController';
import { UploadOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, message, Select, Space, Upload } from 'antd';
import { useForm } from 'antd/es/form/Form';
import TextArea from 'antd/es/input/TextArea';
import React, { useState } from 'react';

/**
 * 添加图表（异步）页面
 * @constructor
 */
const AddChartAsync: React.FC = () => {
  const [form] = useForm();
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [fileList, setFileList] = useState<any[]>([]);

  // 以界面中保留的文件列表为准，修改分析目标后可以再次提交同一个文件。
  const getSelectedFile = () => fileList[0]?.originFileObj as File | undefined;

  /**
   * 提交
   * @param values
   */
  const onFinish = async (values: any) => {
    // 避免重复提交
    if (submitting) {
      return;
    }
    setSubmitting(true);
    // 对接后端，上传数据
    const params = {
      ...values,
      file: undefined,
    };
    try {
      const selectedFile = getSelectedFile();
      if (!selectedFile) {
        message.error('请先选择 XLSX 文件');
        setSubmitting(false);
        return;
      }
      const res = await genChartByAiAsyncUsingPOST(params, {}, selectedFile);
      // 如需切换到 MQ 接口，传入相同的 selectedFile 即可。
      // const res = await genChartByAiAsyncMqUsingPOST(params, {}, selectedFile);
      if (!res?.data) {
        message.error(res?.message || '服务器没有返回分析任务');
      } else {
        message.success(
          '分析任务提交成功，稍后请在我的图表页面查看；已选文件会保留，可修改目标后再次提交',
        );
      }
    } catch (e: any) {
      const reason = e?.response?.data?.message || e?.message || '请稍后重试';
      message.error('分析失败：' + reason);
    }
    setSubmitting(false);
  };

  return (
    <div className="add-chart-async">
      <Card title="智能分析">
        <Form
          form={form}
          name="addChart"
          labelAlign="left"
          labelCol={{ span: 4 }}
          wrapperCol={{ span: 16 }}
          onFinish={onFinish}
          initialValues={{}}
        >
          <Form.Item
            name="goal"
            label="分析目标"
            rules={[{ required: true, message: '请输入分析目标' }]}
          >
            <TextArea placeholder="请输入你的分析需求，比如：分析网站用户的增长情况" />
          </Form.Item>
          <Form.Item name="name" label="图表名称">
            <Input placeholder="请输入图表名称" />
          </Form.Item>
          <Form.Item name="chartType" label="图表类型">
            <Select
              options={[
                { value: '折线图', label: '折线图' },
                { value: '柱状图', label: '柱状图' },
                { value: '堆叠图', label: '堆叠图' },
                { value: '饼图', label: '饼图' },
                { value: '雷达图', label: '雷达图' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="file"
            label="原始数据"
            valuePropName="fileList"
            getValueFromEvent={(event) => event?.fileList ?? []}
            extra="当前页面会保留已选文件；修改分析目标后再次提交即可复用。"
            rules={[
              {
                validator: (_, value) =>
                  value?.length
                    ? Promise.resolve()
                    : Promise.reject(new Error('请先选择 XLSX 文件')),
              },
            ]}
          >
            <Upload
              name="file"
              maxCount={1}
              fileList={fileList}
              beforeUpload={() => false}
              onChange={({ fileList: nextFileList }) => {
                setFileList(nextFileList);
                form.setFieldValue('file', nextFileList);
              }}
              onRemove={() => {
                setFileList([]);
                form.setFieldValue('file', []);
              }}
            >
              <Button icon={<UploadOutlined />}>上传 XLSX 文件（最大 5MB）</Button>
            </Upload>
          </Form.Item>

          <Form.Item wrapperCol={{ span: 16, offset: 4 }}>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting}>
                提交
              </Button>
              <Button
                htmlType="button"
                onClick={() => {
                  form.resetFields();
                  setFileList([]);
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
};
export default AddChartAsync;
